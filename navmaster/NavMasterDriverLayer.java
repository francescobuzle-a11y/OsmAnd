package net.osmand.plus.views.layers;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.FavouritePoint;
import net.osmand.data.LatLon;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NavMaster driver layer (original design):
 *  - red restriction banner for the next height / weight / width / length limit on the route
 *  - "Arrivo" panel with a satellite view of the destination area when getting close
 *  - round "Segnala" (report) and "POI" buttons while navigating
 */
public class NavMasterDriverLayer extends OsmandMapLayer {

	private static final String TAG = "NavMasterDL";
	private static final int ARRIVAL_SHOW_M = 2000;
	private static final int RESTRICTION_LOOKAHEAD_M = 5000;
	private static final int ROUTE_MAGENTA = 0xFFC2189A;
	private static final int BANNER_RED = 0xFFC62828;
	private static final String REPORT_CATEGORY = "NavMaster segnalazioni";
	// Esri World Imagery (attribution shown on the panel)
	private static final String SAT_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d";

	private OsmandApplication app;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Path path = new Path();
	private float dp;

	private final RectF reportBtn = new RectF();
	private final RectF poiBtn = new RectF();
	private boolean buttonsVisible;

	private final LruCache<String, Bitmap> tiles = new LruCache<>(48);
	private final Set<String> loading = new HashSet<>();
	private final java.util.Map<String, Long> failed = new java.util.HashMap<>();
	private final ExecutorService executor = Executors.newFixedThreadPool(2);

	private long lastRestrictionCheck;
	private String restrictionText;
	private int restrictionDist;
	private long lastLog;
	private boolean isLandscapeCanvas;
	private int hudBottomApplied = -1;
	private net.osmand.plus.views.mapwidgets.TurnDrawable turnDrawable;
	private int turnDrawableSize;
	private final RectF simSlower = new RectF();
	private final RectF simFaster = new RectF();
	private final RectF simSkip = new RectF();
	private final RectF simStop = new RectF();
	private boolean simVisible;
	private boolean demoSimStarted;

	// ---- layout: NavMaster panels keep clear of OsmAnd's buttons / widgets and of the system bars
	public static volatile List<RectF> nmHudRects = new java.util.ArrayList<>();
	public static volatile RectF nmCardRect;
	public static volatile RectF nmSlot;
	private final List<RectF> placed = new java.util.ArrayList<>();
	private long lastHudScan;
	private int insetLeft, insetRight, insetBottom, insetTop;
	private float lastPanelH;
	private RectF nmTopBar;
	private float ratioX = -1;
	private float ratioY = -1;
	private android.graphics.Rect hudAreaApplied;
	private long lastPoiEnforce;
	private long lastBtnLog;
	private long lastPoiBtnTap;

	public NavMasterDriverLayer(@NonNull Context ctx) {
		super(ctx);
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		app = getApplication();
		dp = app.getResources().getDisplayMetrics().density;
		stroke.setStyle(Paint.Style.STROKE);
		text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		buttonsVisible = false;
		if (app == null) {
			return;
		}
		try {
			RoutingHelper rh = app.getRoutingHelper();
			boolean night = settings != null && settings.isNightMode();
			int w = canvas.getWidth();
			int h = canvas.getHeight();
			boolean landscape = w > h;
			isLandscapeCanvas = landscape;
			boolean navigating = rh.isFollowingMode() && rh.isRouteCalculated();
			boolean road = nmRoadMode(app.getSettings().getApplicationMode());
			nmMigrate();
			nmCursor();
			enforceNoPoiVoice();
			scanHud(w, h);
			placed.clear();
			if (!navigating || !road) {
				applyHudClear();
				clearMapRatio();
				nmCardRect = null;
				nmSlot = null;
				if (!navigating) {
					return;
				}
			}
			float topBarBottom = insetTop + 8 * dp;
			float botBarTop = h - insetBottom;
			if (road) {
				topBarBottom = drawTopBar(canvas, w, h, landscape, rh, night);
				botBarTop = drawBottomBar(canvas, w, h, landscape, rh, night);
				lastPanelH = h - botBarTop;
			}
			String demo = demoName();
			if (demo != null && demo.contains("NAVMASTER_SIM") && !demoSimStarted) {
				// preview/test hook: start the route simulation automatically
				demoSimStarted = true;
				net.osmand.plus.simulation.OsmAndLocationSimulation sim = app.getLocationProvider().getLocationSimulation();
				MapActivity a = getMapActivity();
				if (sim != null && a != null && !sim.isRouteAnimating()) {
					a.runOnUiThread(() -> sim.startStopRouteAnimation(a));
				}
			}
			boolean junctionShown = System.currentTimeMillis() - JunctionViewLayer.nmPanelShownAt < 900;

			// free slot for the junction / lane / arrival panel: right half in landscape (Garmin-like split),
			// just below the top bar in portrait
			float slotTop = topBarBottom + 8 * dp;
			RectF slot = landscape
					? new RectF(w * 0.54f, slotTop, w - insetRight - 10 * dp, botBarTop - 8 * dp)
					: new RectF(insetLeft + 10 * dp, slotTop, w - insetRight - 10 * dp,
							slotTop + Math.min(w * 0.52f, (botBarTop - slotTop) * 0.46f));
			nmSlot = slot.width() > 140 * dp && slot.height() > 80 * dp ? new RectF(slot) : null;

			// map area: what is left for the map, OsmAnd's buttons and the vehicle cursor
			float mapLeft = insetLeft;
			float mapRight = w - insetRight;
			float mapTop = topBarBottom;
			float mapBottom = botBarTop;
			if (junctionShown && JunctionViewLayer.nmPanelRect != null) {
				RectF jp = JunctionViewLayer.nmPanelRect;
				if (landscape) {
					mapRight = Math.min(mapRight, jp.left - 6 * dp);
				} else {
					mapTop = Math.max(mapTop, jp.bottom + 4 * dp);
				}
			}

			// restriction banner: bottom left of the map area, like the warnings on a Garmin
			updateRestriction(rh, demo);
			float bannerTop = mapTop + 6 * dp;
			if (restrictionText != null) {
				// under the top bar, to the right of OsmAnd's profile / search buttons
				float bl = mapLeft + (landscape ? 76 : 140) * dp;
				float br = mapRight - (landscape ? 10 * dp : 150 * dp);
				RectF banner = new RectF(bl, bannerTop, Math.min(br, bl + 330 * dp), bannerTop + 38 * dp);
				RectF fb = nmFit(banner, obstacles(), 170 * dp, 34 * dp, 6 * dp);
				if (fb != null) {
					banner = fb;
					banner.bottom = banner.top + 38 * dp;
				}
				drawBanner(canvas, banner);
				placed.add(banner);
			}

			// arrival panel with satellite view, in the same slot (the junction view has priority)
			boolean arrivalDemo = demo != null && demo.contains("NAVMASTER_ARRIVO");
			if (!junctionShown && nmSlot != null && (arrivalDemo || rh.getLeftDistance() <= ARRIVAL_SHOW_M)) {
				TargetPoint tp = app.getTargetPointsHelper().getPointToNavigate();
				if (tp != null) {
					RectF panel = new RectF(nmSlot);
					if (!landscape) {
						panel.top = Math.max(panel.top, bannerTop);
					}
					drawArrival(canvas, panel, tp.getLatitude(), tp.getLongitude(), rh, night);
					placed.add(panel);
					if (landscape) {
						mapRight = Math.min(mapRight, panel.left - 6 * dp);
					} else {
						mapTop = Math.max(mapTop, panel.bottom + 4 * dp);
					}
				}
			}
			if (road) {
				applyHudArea(mapLeft, mapTop, mapRight, mapBottom);
				applyMapRatio(w, h, mapLeft, mapTop, mapRight, mapBottom);
			}
			drawButtons(canvas, w, h, landscape, mapLeft, Math.max(mapTop, bannerTop), mapRight, mapBottom);
			long now = System.currentTimeMillis();
			if (now - lastLog > 5000) {
				lastLog = now;
				Log.i(TAG, "draw restriction=" + restrictionText + " left=" + rh.getLeftDistance() + " demo=" + demo);
			}
		} catch (Throwable e) {
			Log.e(TAG, "draw failed", e);
		}
	}

	// OsmAnd's own nearby-POI bar and its voice announcements stay off for the road profiles
	private void enforceNoPoiVoice() {
		long now = System.currentTimeMillis();
		if (now - lastPoiEnforce < 5000) {
			return;
		}
		lastPoiEnforce = now;
		try {
			net.osmand.plus.settings.backend.OsmandSettings st = app.getSettings();
			ApplicationMode m = st.getApplicationMode();
			if (nmRoadMode(m) && (st.SHOW_NEARBY_POI.getModeValue(m) || st.ANNOUNCE_NEARBY_POI.getModeValue(m))) {
				st.SHOW_NEARBY_POI.setModeValue(m, false);
				st.ANNOUNCE_NEARBY_POI.setModeValue(m, false);
			}
		} catch (Throwable e) {
			Log.w(TAG, "poi voice: " + e);
		}
	}

	private void scanHud(int w, int h) {
		long now = System.currentTimeMillis();
		if (now - lastHudScan < 400) {
			return;
		}
		lastHudScan = now;
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		List<RectF> out = new java.util.ArrayList<>();
		try {
			android.view.ViewGroup hud = app.getOsmandMap().getMapLayers().getMapControlsLayer().getMapHudLayout();
			if (hud != null) {
				collectViews(hud, out, w, h, 0);
			}
			View decor = a.getWindow().getDecorView();
			android.view.WindowInsets wi = decor.getRootWindowInsets();
			if (wi != null) {
				int dh = decor.getHeight();
				int dw = decor.getWidth();
				insetBottom = Math.max(0, h - (dh - wi.getStableInsetBottom()));
				insetLeft = Math.max(0, wi.getStableInsetLeft());
				insetRight = Math.max(0, w - (dw - wi.getStableInsetRight()));
				insetTop = Math.min((int) (60 * dp), Math.max(0, wi.getStableInsetTop()));
			}
		} catch (Throwable e) {
			Log.w(TAG, "hud scan: " + e);
		}
		nmHudRects = out;
	}

	private void collectViews(android.view.ViewGroup g, List<RectF> out, int w, int h, int depth) {
		int[] loc = new int[2];
		for (int i = 0; i < g.getChildCount(); i++) {
			View v = g.getChildAt(i);
			if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0 || v.getAlpha() < 0.05f) {
				continue;
			}
			boolean big = v.getWidth() > w * 0.6f || v.getHeight() > h * 0.6f;
			if (big) {
				if (v instanceof android.view.ViewGroup && depth < 3) {
					collectViews((android.view.ViewGroup) v, out, w, h, depth + 1);
				}
				continue;
			}
			v.getLocationInWindow(loc);
			out.add(new RectF(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()));
		}
	}

	private List<RectF> obstacles() {
		List<RectF> o = new java.util.ArrayList<>(nmHudRects);
		if (nmCardRect != null) {
			o.add(nmCardRect);
		}
		o.addAll(placed);
		if (System.currentTimeMillis() - JunctionViewLayer.nmPanelShownAt < 800 && JunctionViewLayer.nmPanelRect != null) {
			o.add(JunctionViewLayer.nmPanelRect);
		}
		return o;
	}

	/** Shrinks r until it no longer overlaps any obstacle; null when it would become smaller than minW x minH. */
	public static RectF nmFit(RectF r, List<RectF> obs, float minW, float minH, float gap) {
		RectF c = new RectF(r);
		for (int it = 0; it < 10; it++) {
			boolean changed = false;
			for (RectF o : obs) {
				if (!RectF.intersects(c, o)) {
					continue;
				}
				RectF[] opts = {
						new RectF(c.left, c.top, o.left - gap, c.bottom),
						new RectF(o.right + gap, c.top, c.right, c.bottom),
						new RectF(c.left, o.bottom + gap, c.right, c.bottom),
						new RectF(c.left, c.top, c.right, o.top - gap)};
				RectF best = null;
				float bestA = -1;
				for (RectF op : opts) {
					if (op.width() >= minW && op.height() >= minH && op.width() * op.height() > bestA) {
						bestA = op.width() * op.height();
						best = op;
					}
				}
				if (best == null) {
					return null;
				}
				c = best;
				changed = true;
			}
			if (!changed) {
				break;
			}
		}
		return c;
	}

	/** Used by the junction view: fits its panel between OsmAnd's widgets, buttons and the NavMaster card. */
	public static RectF nmFitPanel(RectF r, float minW, float minH, float gap) {
		List<RectF> o = new java.util.ArrayList<>(nmHudRects);
		if (nmCardRect != null) {
			o.add(nmCardRect);
		}
		return nmFit(r, o, minW, minH, gap);
	}

	private boolean migrated;
	private boolean cursorSet;

	// Garmin-like cursor: a real 3D vehicle model lying on the road, moving smoothly between fixes
	private void nmCursor() {
		if (cursorSet) {
			return;
		}
		cursorSet = true;
		try {
			net.osmand.plus.settings.backend.OsmandSettings st = app.getSettings();
			net.osmand.plus.settings.backend.preferences.CommonPreference<Boolean> done =
					st.registerBooleanPreference("nm_cursor_v1", false).makeGlobal();
			if (done.get()) {
				return;
			}
			String nav = modelIfPresent("map_navigation_car");
			String still = modelIfPresent("map_car_location");
			for (ApplicationMode m : ApplicationMode.allPossibleValues()) {
				if (!nmRoadMode(m)) {
					continue;
				}
				if (nav != null) {
					st.NAVIGATION_ICON.setModeValue(m, nav);
				}
				if (still != null) {
					st.LOCATION_ICON.setModeValue(m, still);
				}
				st.ANIMATE_MY_LOCATION.setModeValue(m, true);
				st.LOCATION_INTERPOLATION_PERCENT.setModeValue(m, 100);
			}
			done.set(true);
			Log.i(TAG, "cursor set: " + nav + " / " + still);
		} catch (Throwable e) {
			Log.w(TAG, "cursor: " + e);
		}
	}

	private String modelIfPresent(String name) {
		try {
			java.io.File dir = new java.io.File(app.getAppPath(net.osmand.IndexConstants.MODEL_3D_DIR), name);
			if (net.osmand.plus.helpers.Model3dHelper.isModelExist(dir)) {
				return net.osmand.IndexConstants.MODEL_NAME_PREFIX + name;
			}
		} catch (Throwable e) {
			Log.w(TAG, "model " + name + ": " + e);
		}
		return null;
	}

	// one-time clean-up: no POI voice announcements / top POI bar, NavMaster keeps its own POI categories
	private void nmMigrate() {
		if (migrated) {
			return;
		}
		migrated = true;
		try {
			net.osmand.plus.settings.backend.OsmandSettings st = app.getSettings();
			net.osmand.plus.settings.backend.preferences.CommonPreference<Boolean> done =
					st.registerBooleanPreference("nm_voice_poi_fix_v1", false).makeGlobal();
			if (done.get()) {
				return;
			}
			for (ApplicationMode m : ApplicationMode.allPossibleValues()) {
				if (nmRoadMode(m)) {
					st.SHOW_NEARBY_POI.setModeValue(m, false);
					st.ANNOUNCE_NEARBY_POI.setModeValue(m, false);
					st.SHOW_NEARBY_FAVORITES.setModeValue(m, false);
				}
			}
			ensurePoiPrefs();
			StringBuilder cats = new StringBuilder();
			for (String id : POI_IDS) {
				PoiUIFilter f = app.getPoiFilters().getFilterById(PoiUIFilter.STD_PREFIX + id);
				if (f != null && app.getPoiFilters().isPoiFilterSelected(f)) {
					cats.append(cats.length() > 0 ? "," : "").append(id);
					app.getPoiFilters().removeSelectedPoiFilter(f);
				}
			}
			if (cats.length() > 0) {
				poiCatsPref.set(cats.toString());
			}
			done.set(true);
		} catch (Throwable e) {
			Log.w(TAG, "migrate: " + e);
		}
	}

	private String demoName() {
		TargetPoint tp = app.getTargetPointsHelper().getPointToNavigate();
		String n = tp != null ? tp.getOnlyName() : null;
		return n != null && n.contains("NAVMASTER_") ? n : null;
	}

	private float topOffset(int canvasHeight) {
		MapActivity activity = getMapActivity();
		if (activity != null) {
			View topPanel = activity.findViewById(R.id.top_widgets_panel);
			if (topPanel != null && topPanel.getVisibility() == View.VISIBLE && topPanel.getHeight() > 0) {
				int[] loc = new int[2];
				topPanel.getLocationInWindow(loc);
				return loc[1] + topPanel.getHeight() + 6 * dp;
			}
		}
		return 30 * dp + (isLandscapeCanvas ? 40 * dp : 0);
	}

	// ---------------------------------------------------------------- restrictions

	private void updateRestriction(RoutingHelper rh, String demo) {
		long now = System.currentTimeMillis();
		if (now - lastRestrictionCheck < 2000) {
			return;
		}
		lastRestrictionCheck = now;
		restrictionText = null;
		if (demo != null) {
			restrictionDist = 850;
			restrictionText = label("maxheight") + " 3,8 m";
		} else {
			findRestriction(rh.getRoute());
		}
		if (restrictionText != null) {
			restrictionText = restrictionText + "  ·  " + (restrictionDist < 30
					? (italian() ? "su questa strada" : "on this road")
					: OsmAndFormatter.getFormattedDistance(restrictionDist, app));
		}
	}

	private void findRestriction(RouteCalculationResult route) {
		if (route == null) {
			return;
		}
		List<RouteSegmentResult> segs = route.getImmutableAllSegments();
		if (segs == null || segs.isEmpty()) {
			return;
		}
		int cs = Math.max(0, route.getCurrentRoute() - 1);
		float dist = 0;
		RouteSegmentResult prev = null;
		String[] tags = {"maxheight", "maxweight", "maxwidth", "maxlength", "maxaxleload"};
		while (cs < segs.size() && dist < RESTRICTION_LOOKAHEAD_M) {
			RouteSegmentResult s = segs.get(cs);
			if (s != prev) {
				RouteDataObject o = s.getObject();
				if (o != null) {
					for (String tag : tags) {
						String v = o.getValue(tag);
						if (!Algorithms.isEmpty(v) && !"none".equals(v) && !"default".equals(v)) {
							restrictionDist = (int) dist;
							restrictionText = label(tag) + " " + formatValue(tag, v);
							return;
						}
					}
				}
				if (prev != null) {
					dist += s.getDistance();
				}
				prev = s;
			}
			cs++;
		}
	}

	private boolean italian() {
		return Locale.getDefault().getLanguage().equals("it");
	}

	private String label(String tag) {
		boolean it = italian();
		switch (tag) {
			case "maxheight": return it ? "Altezza max" : "Height limit";
			case "maxweight": return it ? "Peso max" : "Weight limit";
			case "maxwidth": return it ? "Larghezza max" : "Width limit";
			case "maxlength": return it ? "Lunghezza max" : "Length limit";
			default: return it ? "Peso per asse max" : "Axle load limit";
		}
	}

	private String formatValue(String tag, String v) {
		String t = v.trim();
		try {
			float f = Float.parseFloat(t);
			String num = (f == Math.round(f) ? String.valueOf(Math.round(f)) : String.valueOf(f));
			if (italian()) {
				num = num.replace('.', ',');
			}
			boolean weight = tag.equals("maxweight") || tag.equals("maxaxleload");
			return num + (weight ? " t" : " m");
		} catch (NumberFormatException e) {
			return t;
		}
	}

	private void drawBanner(Canvas c, RectF r) {
		card(c, r, r.height() / 2f);
		float s = r.height() - 10 * dp;
		int icon = (int) s;
		c.drawBitmap(NavMasterIcons.get("rep_limit", icon), r.left + 5 * dp, r.top + 5 * dp, bmp);
		String[] parts = restrictionText.split("  ·  ");
		float tx = r.left + 5 * dp + icon + 10 * dp;
		float ts = 15 * dp;
		text.setTextAlign(Paint.Align.LEFT);
		text.setTextSize(ts);
		String main = parts[0];
		String dist = parts.length > 1 ? parts[1] : "";
		Paint dp2 = new Paint(text);
		dp2.setTextSize(13 * dp);
		float avail = r.right - tx - 14 * dp - dp2.measureText(dist) - 8 * dp;
		while (text.measureText(main) > avail && ts > 10 * dp) {
			ts -= dp;
			text.setTextSize(ts);
		}
		if (text.measureText(main) > avail) {
			while (main.length() > 2 && text.measureText(main + "\u2026") > avail) {
				main = main.substring(0, main.length() - 1);
			}
			main = main + "\u2026";
		}
		text.setColor(Color.WHITE);
		c.drawText(main, tx, r.centerY() + ts * 0.36f, text);
		dp2.setTextAlign(Paint.Align.RIGHT);
		dp2.setColor(0xFFFF8A80);
		c.drawText(dist, r.right - 14 * dp, r.centerY() + 13 * dp * 0.36f, dp2);
	}

	// ---------------------------------------------------------------- arrival satellite panel

	private static double tileX(double lon, int z) {
		return (lon + 180.0) / 360.0 * (1 << z);
	}

	private static double tileY(double lat, int z) {
		double r = Math.toRadians(lat);
		return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << z);
	}

	private void drawArrival(Canvas c, RectF p, double lat, double lon, RoutingHelper rh, boolean night) {
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(0x66000000);
		c.drawRoundRect(new RectF(p.left + 3 * dp, p.top + 4 * dp, p.right + 3 * dp, p.bottom + 4 * dp), 10 * dp, 10 * dp, fill);
		fill.setColor(0xFF2B3A2E);
		c.drawRoundRect(p, 10 * dp, 10 * dp, fill);

		float header = 30 * dp;
		RectF map = new RectF(p.left, p.top + header, p.right, p.bottom);
		int z = 17;
		Location me = app.getLocationProvider().getLastKnownLocation();
		if (me != null) {
			// zoom so that both vehicle and destination fit, between 15 and 18
			for (z = 18; z > 15; z--) {
				double dx = Math.abs(tileX(me.getLongitude(), z) - tileX(lon, z)) * 256;
				double dy = Math.abs(tileY(me.getLatitude(), z) - tileY(lat, z)) * 256;
				float scale = dp;
				if (dx * scale < map.width() * 0.45 && dy * scale < map.height() * 0.45) {
					break;
				}
			}
		}
		double cx = tileX(lon, z);
		double cy = tileY(lat, z);
		float tileSize = 256 * dp;
		c.save();
		path.reset();
		path.addRoundRect(map, new float[]{0, 0, 0, 0, 10 * dp, 10 * dp, 10 * dp, 10 * dp}, Path.Direction.CW);
		c.clipPath(path);
		fill.setColor(0xFF3C4A3C);
		c.drawRect(map, fill);
		int minTx = (int) Math.floor(cx - (map.width() / 2f) / tileSize);
		int maxTx = (int) Math.floor(cx + (map.width() / 2f) / tileSize);
		int minTy = (int) Math.floor(cy - (map.height() / 2f) / tileSize);
		int maxTy = (int) Math.floor(cy + (map.height() / 2f) / tileSize);
		boolean missing = false;
		for (int tx = minTx; tx <= maxTx; tx++) {
			for (int ty = minTy; ty <= maxTy; ty++) {
				Bitmap b = getTile(z, tx, ty);
				float left = (float) (map.centerX() + (tx - cx) * tileSize);
				float topY = (float) (map.centerY() + (ty - cy) * tileSize);
				if (b != null) {
					c.drawBitmap(b, null, new RectF(left, topY, left + tileSize, topY + tileSize), bmp);
				} else {
					missing = true;
				}
			}
		}
		// route line from the current position to the destination
		RouteCalculationResult route = rh.getRoute();
		List<Location> locs = route != null ? route.getImmutableAllLocations() : null;
		if (locs != null && !locs.isEmpty()) {
			int start = Math.max(0, route.getCurrentRoute() - 1);
			path.reset();
			boolean first = true;
			if (me != null) {
				path.moveTo(px(me.getLongitude(), z, cx, map, tileSize), py(me.getLatitude(), z, cy, map, tileSize));
				first = false;
			}
			for (int i = start; i < locs.size(); i++) {
				Location l = locs.get(i);
				float x = px(l.getLongitude(), z, cx, map, tileSize);
				float y = py(l.getLatitude(), z, cy, map, tileSize);
				if (first) {
					path.moveTo(x, y);
					first = false;
				} else {
					path.lineTo(x, y);
				}
			}
			stroke.setStrokeCap(Paint.Cap.ROUND);
			stroke.setStrokeJoin(Paint.Join.ROUND);
			stroke.setColor(0xCCFFFFFF);
			stroke.setStrokeWidth(9 * dp);
			c.drawPath(path, stroke);
			stroke.setColor(ROUTE_MAGENTA);
			stroke.setStrokeWidth(6 * dp);
			c.drawPath(path, stroke);
		}
		// vehicle
		if (me != null) {
			float x = px(me.getLongitude(), z, cx, map, tileSize);
			float y = py(me.getLatitude(), z, cy, map, tileSize);
			fill.setColor(Color.WHITE);
			c.drawCircle(x, y, 9 * dp, fill);
			fill.setColor(0xFF1E6FE0);
			c.drawCircle(x, y, 6.5f * dp, fill);
		}
		// destination: checkered flag
		drawFlag(c, (float) map.centerX(), (float) map.centerY());
		if (missing) {
			text.setTextAlign(Paint.Align.CENTER);
			text.setTextSize(12 * dp);
			text.setColor(0xCCFFFFFF);
			c.drawText(italian() ? "Caricamento vista satellitare…" : "Loading satellite view…", map.centerX(), map.bottom - 26 * dp, text);
		}
		// attribution
		text.setTextAlign(Paint.Align.RIGHT);
		text.setTextSize(9 * dp);
		fill.setColor(0x88000000);
		String attr = "Esri, Maxar, Earthstar Geographics";
		c.drawRect(map.right - text.measureText(attr) - 10 * dp, map.bottom - 14 * dp, map.right, map.bottom, fill);
		text.setColor(Color.WHITE);
		c.drawText(attr, map.right - 5 * dp, map.bottom - 4 * dp, text);
		c.restore();

		// header
		text.setTextAlign(Paint.Align.LEFT);
		text.setTextSize(15 * dp);
		text.setColor(Color.WHITE);
		String title = italian() ? "Arrivo" : "Arrival";
		c.drawText(title, p.left + 12 * dp, p.top + 21 * dp, text);
		String dist = OsmAndFormatter.getFormattedDistance(rh.getLeftDistance(), app);
		text.setTextAlign(Paint.Align.RIGHT);
		text.setColor(0xFFB9F6CA);
		c.drawText(dist, p.right - 12 * dp, p.top + 21 * dp, text);
	}

	private static float px(double lon, int z, double cx, RectF map, float tileSize) {
		return (float) (map.centerX() + (tileX(lon, z) - cx) * tileSize);
	}

	private static float py(double lat, int z, double cy, RectF map, float tileSize) {
		return (float) (map.centerY() + (tileY(lat, z) - cy) * tileSize);
	}

	private void drawFlag(Canvas c, float x, float y) {
		float pole = 30 * dp;
		stroke.setColor(Color.BLACK);
		stroke.setStrokeWidth(3 * dp);
		c.drawLine(x, y, x, y - pole, stroke);
		float fw = 22 * dp, fh = 15 * dp;
		float fx = x, fy = y - pole;
		fill.setColor(Color.WHITE);
		c.drawRect(fx, fy, fx + fw, fy + fh, fill);
		fill.setColor(Color.BLACK);
		int cols = 4, rows = 3;
		for (int i = 0; i < cols; i++) {
			for (int j = 0; j < rows; j++) {
				if ((i + j) % 2 == 0) {
					c.drawRect(fx + i * fw / cols, fy + j * fh / rows, fx + (i + 1) * fw / cols, fy + (j + 1) * fh / rows, fill);
				}
			}
		}
		stroke.setStrokeWidth(1.5f * dp);
		c.drawRect(fx, fy, fx + fw, fy + fh, stroke);
		fill.setColor(0x88000000);
		c.drawCircle(x, y, 4 * dp, fill);
	}

	private Bitmap getTile(int z, int x, int y) {
		int n = 1 << z;
		if (y < 0 || y >= n) {
			return null;
		}
		x = ((x % n) + n) % n;
		String key = z + "/" + x + "/" + y;
		Bitmap b = tiles.get(key);
		if (b != null) {
			return b;
		}
		synchronized (loading) {
			Long failedAt = failed.get(key);
			if (loading.contains(key) || (failedAt != null && System.currentTimeMillis() - failedAt < 30000)) {
				return null;
			}
			loading.add(key);
		}
		final int fx = x;
		executor.execute(() -> {
			try {
				File dir = new File(app.getCacheDir(), "navmaster_sat");
				File f = new File(dir, z + "_" + fx + "_" + y + ".jpg");
				Bitmap res = null;
				if (f.exists()) {
					res = BitmapFactory.decodeFile(f.getAbsolutePath());
				}
				if (res == null) {
					HttpURLConnection conn = (HttpURLConnection) new URL(String.format(Locale.US, SAT_URL, z, y, fx)).openConnection();
					conn.setConnectTimeout(8000);
					conn.setReadTimeout(10000);
					conn.setRequestProperty("User-Agent", "NavMaster/1.0 (OsmAnd based)");
					if (conn.getResponseCode() == 200) {
						dir.mkdirs();
						try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(f)) {
							byte[] buf = new byte[16384];
							int r;
							while ((r = in.read(buf)) > 0) {
								out.write(buf, 0, r);
							}
						}
						res = BitmapFactory.decodeFile(f.getAbsolutePath());
					}
					conn.disconnect();
				}
				if (res == null) {
					synchronized (loading) {
						failed.put(key, System.currentTimeMillis());
					}
				} else {
					tiles.put(key, res);
					if (view != null) {
						view.refreshMap();
					}
				}
			} catch (Exception e) {
				Log.w(TAG, "tile " + key + " failed: " + e);
				synchronized (loading) {
					failed.put(key, System.currentTimeMillis());
				}
			} finally {
				synchronized (loading) {
					loading.remove(key);
				}
			}
		});
		return null;
	}

	// ---------------------------------------------------------------- Sygic-style bottom panel

	// keeps OsmAnd's map buttons and widgets inside the free map area
	private void applyHudArea(float l, float t, float r, float b) {
		android.graphics.Rect want = new android.graphics.Rect((int) l, (int) t, (int) r, (int) b);
		if (want.equals(hudAreaApplied)) {
			return;
		}
		hudAreaApplied = want;
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		a.runOnUiThread(() -> {
			try {
				net.osmand.plus.views.controls.MapHudLayout hud =
						app.getOsmandMap().getMapLayers().getMapControlsLayer().getMapHudLayout();
				if (hud == null || hud.getWidth() <= 0) {
					hudAreaApplied = null;
					return;
				}
				int[] loc = new int[2];
				hud.getLocationOnScreen(loc);
				int[] win = new int[2];
				hud.getLocationInWindow(win);
				android.graphics.Rect area = new android.graphics.Rect(want);
				area.offset(loc[0] - win[0], loc[1] - win[1]);
				area.left = Math.max(area.left, loc[0]);
				area.top = Math.max(area.top, loc[1]);
				area.right = Math.min(area.right, loc[0] + hud.getWidth());
				area.bottom = Math.min(area.bottom, loc[1] + hud.getHeight());
				if (area.width() < 120 || area.height() < 120) {
					hud.clearExternalVisibleArea();
				} else {
					hud.setExternalVisibleArea(area);
				}
			} catch (Throwable e) {
				Log.w(TAG, "hud area: " + e);
			}
		});
	}

	private void applyHudClear() {
		if (hudAreaApplied == null) {
			return;
		}
		hudAreaApplied = null;
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		a.runOnUiThread(() -> {
			try {
				net.osmand.plus.views.controls.MapHudLayout hud =
						app.getOsmandMap().getMapLayers().getMapControlsLayer().getMapHudLayout();
				if (hud != null) {
					hud.clearExternalVisibleArea();
				}
			} catch (Throwable e) {
				Log.w(TAG, "hud area: " + e);
			}
		});
	}

	// keeps the vehicle cursor inside the free map area: it must never end up under the bottom bar
	private void applyMapRatio(int w, int h, float l, float t, float r, float b) {
		if (w <= 0 || h <= 0 || b - t < 100 * dp || r - l < 100 * dp) {
			return;
		}
		float rx = Math.max(0.2f, Math.min(0.8f, ((l + r) / 2f) / w));
		float ry = Math.max(0.25f, Math.min(0.85f, (t + (b - t) * 0.72f) / h));
		if (Math.abs(rx - ratioX) < 0.02f && Math.abs(ry - ratioY) < 0.02f) {
			return;
		}
		ratioX = rx;
		ratioY = ry;
		try {
			app.getMapViewTrackingUtilities().getMapDisplayPositionManager().setCustomMapRatio(rx, ry);
		} catch (Throwable e) {
			Log.w(TAG, "map ratio: " + e);
		}
	}

	private void clearMapRatio() {
		if (ratioX < 0) {
			return;
		}
		ratioX = -1;
		ratioY = -1;
		try {
			app.getMapViewTrackingUtilities().getMapDisplayPositionManager().restoreMapRatio();
		} catch (Throwable e) {
			Log.w(TAG, "map ratio: " + e);
		}
	}

	private String[] timeLeft(int s) {
		if (s >= 3600) {
			return new String[]{(s / 3600) + ":" + String.format(Locale.US, "%02d", (s % 3600) / 60), "h"};
		}
		return new String[]{String.valueOf(Math.max(0, (s + 30) / 60)), "min"};
	}

	private void drawValue(Canvas c, String value, String unit, float cx, float baseline, float size, int color, Paint.Align align) {
		text.setTextSize(size);
		Paint up = new Paint(text);
		up.setTextSize(size * 0.45f);
		up.setTextAlign(Paint.Align.LEFT);
		float vw = text.measureText(value);
		float uw = unit.isEmpty() ? 0 : up.measureText(unit) + 2 * dp;
		float x = align == Paint.Align.CENTER ? cx - (vw + uw) / 2f : (align == Paint.Align.RIGHT ? cx - vw - uw : cx);
		text.setTextAlign(Paint.Align.LEFT);
		text.setColor(color);
		c.drawText(value, x, baseline, text);
		if (!unit.isEmpty()) {
			up.setColor(0xFFB8C0CA);
			c.drawText(unit, x + vw + 2 * dp, baseline, up);
		}
	}

	// ---- NavMaster "glass" style helpers
	private static final int CARD_TOP = 0xF2232830;
	private static final int CARD_BOTTOM = 0xF2121519;
	private static final int ACCENT = 0xFF2EB85C;
	private static final int ACCENT_2 = 0xFF1FA2A6;
	private static final int MUTED = 0xFF9AA4B0;

	private void card(Canvas c, RectF r, float radius) {
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(0x40000000);
		c.drawRoundRect(new RectF(r.left, r.top + 4 * dp, r.right, r.bottom + 6 * dp), radius, radius, fill);
		fill.setColor(0xFFFFFFFF);
		fill.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, CARD_TOP, CARD_BOTTOM,
				android.graphics.Shader.TileMode.CLAMP));
		c.drawRoundRect(r, radius, radius, fill);
		fill.setShader(null);
		stroke.setShader(null);
		stroke.setColor(0x22FFFFFF);
		stroke.setStrokeWidth(1f * dp);
		c.drawRoundRect(new RectF(r.left + 0.5f * dp, r.top + 0.5f * dp, r.right - 0.5f * dp, r.bottom - 0.5f * dp), radius, radius, stroke);
	}

	private void caption(Canvas c, String t, float x, float y, Paint.Align align) {
		text.setTextSize(10.5f * dp);
		text.setTextAlign(align);
		text.setColor(MUTED);
		text.setLetterSpacing(0.08f);
		c.drawText(t.toUpperCase(Locale.getDefault()), x, y, text);
		text.setLetterSpacing(0f);
	}

	// Garmin-like top bar: distance to the next manoeuvre, turn icon and the road it leads to
	private float drawTopBar(Canvas c, int w, int h, boolean landscape, RoutingHelper rh, boolean night) {
		float m = 8 * dp;
		float barH = (landscape ? 52 : 58) * dp;
		RectF bar = new RectF(insetLeft + m, insetTop + m, w - insetRight - m, insetTop + m + barH);
		float r = 14 * dp;
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(0x40000000);
		c.drawRoundRect(new RectF(bar.left, bar.top + 3 * dp, bar.right, bar.bottom + 5 * dp), r, r, fill);
		fill.setColor(0xFFFFFFFF);
		fill.setShader(new android.graphics.LinearGradient(0, bar.top, 0, bar.bottom, 0xFF1B8B47, 0xFF0D5A2C,
				android.graphics.Shader.TileMode.CLAMP));
		c.drawRoundRect(bar, r, r, fill);
		fill.setShader(null);

		net.osmand.plus.routing.NextDirectionInfo next =
				rh.getNextRouteDirectionInfo(new net.osmand.plus.routing.NextDirectionInfo(), true);
		float x = bar.left + 14 * dp;
		if (next != null && next.directionInfo != null) {
			net.osmand.plus.utils.FormattedValue nd = OsmAndFormatter.getFormattedDistanceValue(next.distanceTo, app);
			float sz = barH * 0.50f;
			text.setTextSize(sz);
			float vw = text.measureText(nd.value);
			Paint u = new Paint(text);
			u.setTextSize(sz * 0.45f);
			drawValue(c, nd.value, nd.unit, x, bar.centerY() + sz * 0.36f, sz, Color.WHITE, Paint.Align.LEFT);
			x += vw + u.measureText(nd.unit) + 16 * dp;
			MapActivity a = getMapActivity();
			net.osmand.router.TurnType tt = next.directionInfo.getTurnType();
			if (a != null && tt != null) {
				int size = (int) (barH * 0.68f);
				if (turnDrawable == null || turnDrawableSize != size) {
					turnDrawable = new net.osmand.plus.views.mapwidgets.TurnDrawable(a, false);
					turnDrawable.setBounds(0, 0, size, size);
					turnDrawableSize = size;
					turnDrawable.setRouteDirectionColor(android.R.color.white);
					turnDrawable.updateColors(true);
				}
				turnDrawable.setTurnType(tt);
				c.save();
				c.translate(x, bar.centerY() - size / 2f);
				turnDrawable.draw(c);
				c.restore();
				x += size + 12 * dp;
			}
		}
		String name = nextRoadName(next);
		if (!Algorithms.isEmpty(name)) {
			text.setTextAlign(Paint.Align.LEFT);
			text.setColor(0xFFFFFFFF);
			text.setTextSize(barH * 0.33f);
			float maxW = bar.right - 14 * dp - x;
			String sName = name;
			if (text.measureText(sName) > maxW) {
				while (sName.length() > 2 && text.measureText(sName + "\u2026") > maxW) {
					sName = sName.substring(0, sName.length() - 1);
				}
				sName = sName + "\u2026";
			}
			c.drawText(sName, x, bar.centerY() + barH * 0.12f, text);
		}
		nmTopBar = new RectF(bar);
		placed.add(new RectF(bar));
		return bar.bottom;
	}

	private String nextRoadName(net.osmand.plus.routing.NextDirectionInfo next) {
		try {
			if (next != null && next.directionInfo != null) {
				net.osmand.plus.routing.RouteDirectionInfo di = next.directionInfo;
				String dest = di.getDestinationRefAndName();
				if (!Algorithms.isEmpty(dest)) {
					return dest.split("[;,]")[0].trim();
				}
				String ref = di.getRef();
				String street = di.getStreetName();
				if (!Algorithms.isEmpty(ref) && !Algorithms.isEmpty(street)) {
					return ref + "  \u00b7  " + street;
				}
				if (!Algorithms.isEmpty(ref)) {
					return ref;
				}
				if (!Algorithms.isEmpty(street)) {
					return street;
				}
			}
		} catch (Throwable e) {
			Log.w(TAG, "road name: " + e);
		}
		return null;
	}

	// slim bottom bar: speed, the road you are on, arrival time; returns its top (map area limit)
	private float drawBottomBar(Canvas c, int w, int h, boolean landscape, RoutingHelper rh, boolean night) {
		boolean it = italian();
		net.osmand.plus.simulation.OsmAndLocationSimulation sim = app.getLocationProvider().getLocationSimulation();
		boolean simOn = sim != null && sim.isRouteAnimating();
		float m = 8 * dp;
		float barH = (landscape ? 56 : 62) * dp;
		float simH = simOn ? 44 * dp : 0;
		float safeL = insetLeft + m;
		float safeR = w - insetRight - m;
		float safeB = h - insetBottom - m;
		float bw = Math.min(safeR - safeL, landscape ? 900 * dp : 100000 * dp);
		float ccx = (safeL + safeR) / 2f;
		RectF bar = new RectF(ccx - bw / 2f, safeB - barH, ccx + bw / 2f, safeB);
		card(c, bar, 16 * dp);
		nmCardRect = new RectF(bar);

		// route progress hairline with the next restriction marked on it
		float bx0 = bar.left + 16 * dp;
		float bx1 = bar.right - 16 * dp;
		float barY = bar.top + 9 * dp;
		int leftM = rh.getLeftDistance();
		stroke.setShader(null);
		stroke.setStrokeCap(Paint.Cap.ROUND);
		stroke.setStrokeWidth(3 * dp);
		stroke.setColor(0x33FFFFFF);
		c.drawLine(bx0, barY, bx1, barY, stroke);
		stroke.setShader(new android.graphics.LinearGradient(bx0, 0, bx1, 0, ACCENT, ACCENT_2,
				android.graphics.Shader.TileMode.CLAMP));
		c.drawLine(bx0, barY, bx1, barY, stroke);
		stroke.setShader(null);
		fill.setStyle(Paint.Style.FILL);
		if (restrictionText != null && leftM > 0 && restrictionDist < leftM) {
			float rxp = bx0 + restrictionDist / (float) leftM * (bx1 - bx0);
			fill.setColor(Color.WHITE);
			c.drawCircle(rxp, barY, 4.5f * dp, fill);
			fill.setColor(BANNER_RED);
			c.drawCircle(rxp, barY, 3.2f * dp, fill);
		}
		fill.setColor(Color.WHITE);
		c.drawCircle(bx0, barY, 4.5f * dp, fill);
		fill.setColor(ACCENT);
		c.drawCircle(bx0, barY, 3f * dp, fill);

		float cellTop = barY + 6 * dp;
		float cy = (cellTop + bar.bottom) / 2f - 2 * dp;
		float colW = Math.min(bar.width() * 0.30f, 150 * dp);
		float big = (landscape ? 22 : 23) * dp;

		// left: current speed (red pill above the limit)
		Location me = app.getLocationProvider().getLastKnownLocation();
		float mps = me != null && me.hasSpeed() ? me.getSpeed() : 0;
		net.osmand.plus.utils.FormattedValue sp = OsmAndFormatter.getFormattedSpeedValue(mps, app);
		float limit = rh.getCurrentMaxSpeed();
		boolean speeding = limit > 0 && mps > limit + 1.5f;
		float lcx = bar.left + colW / 2f;
		if (speeding) {
			text.setTextSize(big);
			Paint u = new Paint(text);
			u.setTextSize(big * 0.45f);
			float pw = text.measureText(sp.value) + u.measureText(sp.unit) + 22 * dp;
			RectF pill = new RectF(lcx - pw / 2f, cy - big * 0.74f, lcx + pw / 2f, cy + big * 0.50f);
			fill.setColor(0xFFE53935);
			c.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, fill);
		}
		drawValue(c, sp.value, sp.unit, lcx, cy + big * 0.32f, big, Color.WHITE, Paint.Align.CENTER);
		caption(c, it ? "velocit\u00e0" : "speed", lcx, bar.bottom - 8 * dp, Paint.Align.CENTER);

		// right: arrival time, distance and time left
		int leftS = rh.getLeftTime();
		String eta = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis() + leftS * 1000L));
		net.osmand.plus.utils.FormattedValue dist = OsmAndFormatter.getFormattedDistanceValue(leftM, app);
		String[] tl = timeLeft(leftS);
		float rcx = bar.right - colW / 2f;
		drawValue(c, eta, "", rcx, cy + big * 0.32f, big, Color.WHITE, Paint.Align.CENTER);
		text.setTextSize(11 * dp);
		text.setTextAlign(Paint.Align.CENTER);
		text.setColor(0xFFB8C0CA);
		c.drawText(dist.value + " " + dist.unit + "  \u00b7  " + tl[0] + " " + tl[1], rcx, bar.bottom - 8 * dp, text);

		// centre: the road you are driving on
		String street = null;
		try {
			net.osmand.plus.routing.CurrentStreetName sn = rh.getCurrentName(
					rh.getNextRouteDirectionInfo(new net.osmand.plus.routing.NextDirectionInfo(), true), false);
			street = sn != null ? sn.text : null;
		} catch (Throwable e) {
			// optional
		}
		if (!Algorithms.isEmpty(street)) {
			float maxW = bar.width() - 2 * colW - 16 * dp;
			text.setTextSize((landscape ? 17 : 16) * dp);
			text.setTextAlign(Paint.Align.CENTER);
			text.setColor(Color.WHITE);
			String sName = street;
			if (text.measureText(sName) > maxW) {
				while (sName.length() > 3 && text.measureText(sName + "\u2026") > maxW) {
					sName = sName.substring(0, sName.length() - 1);
				}
				sName = sName + "\u2026";
			}
			c.drawText(sName, bar.centerX(), cy + 6 * dp, text);
		}
		stroke.setColor(0x1FFFFFFF);
		stroke.setStrokeWidth(1 * dp);
		stroke.setStrokeCap(Paint.Cap.BUTT);
		c.drawLine(bar.left + colW, cellTop + 2 * dp, bar.left + colW, bar.bottom - 10 * dp, stroke);
		c.drawLine(bar.right - colW, cellTop + 2 * dp, bar.right - colW, bar.bottom - 10 * dp, stroke);

		if (simOn) {
			float rw = Math.min(bar.width(), 330 * dp);
			RectF row = new RectF(bar.centerX() - rw / 2f, bar.top - simH - 6 * dp, bar.centerX() + rw / 2f, bar.top - 6 * dp);
			card(c, row, 14 * dp);
			drawSimControls(c, row);
			nmCardRect = new RectF(Math.min(bar.left, row.left), row.top, Math.max(bar.right, row.right), bar.bottom);
			return row.top;
		}
		simVisible = false;
		return bar.top;
	}

	// simulation controls row inside the card: slower / speed / faster / skip 1 km / stop
	private void drawSimControls(Canvas c, RectF row) {
		simVisible = true;
		boolean it = italian();
		float d = 34 * dp;
		float gap = 9 * dp;
		float labelW = 60 * dp;
		float total = 4 * d + 4 * gap + labelW;
		float x = row.centerX() - total / 2f;
		float cy = row.centerY() + 3 * dp;
		simSlower.set(x, cy - d / 2f, x + d, cy + d / 2f);
		x += d + gap;
		RectF label = new RectF(x, cy - d / 2f, x + labelW, cy + d / 2f);
		x += labelW + gap;
		simFaster.set(x, cy - d / 2f, x + d, cy + d / 2f);
		x += d + gap;
		simSkip.set(x, cy - d / 2f, x + d, cy + d / 2f);
		x += d + gap;
		simStop.set(x, cy - d / 2f, x + d, cy + d / 2f);
		simButton(c, simSlower, "−", 0x33FFFFFF);
		simButton(c, simFaster, "+", 0x33FFFFFF);
		simButton(c, simSkip, "»", 0x33FFFFFF);
		simButton(c, simStop, "■", 0xCCE53935);
		text.setTextAlign(Paint.Align.CENTER);
		text.setColor(Color.WHITE);
		text.setTextSize(17 * dp);
		float f = net.osmand.plus.simulation.OsmAndLocationSimulation.nmSpeedFactor;
		String fs = f == Math.round(f) ? String.valueOf(Math.round(f)) : String.valueOf(f);
		c.drawText("×" + fs, label.centerX(), label.centerY() + 1 * dp, text);
		caption(c, it ? "simulazione" : "simulation", label.centerX(), row.bottom - 4 * dp, Paint.Align.CENTER);
	}

	private void simButton(Canvas c, RectF r, String s, int bg) {
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(bg);
		c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, fill);
		text.setTextAlign(Paint.Align.CENTER);
		text.setColor(Color.WHITE);
		text.setTextSize(20 * dp);
		c.drawText(s, r.centerX(), r.centerY() + 7 * dp, text);
	}

	private boolean onSimTap(PointF p) {
		if (!simVisible) {
			return false;
		}
		float pad = 4 * dp;
		float f = net.osmand.plus.simulation.OsmAndLocationSimulation.nmSpeedFactor;
		if (inflate(simSlower, pad).contains(p.x, p.y)) {
			net.osmand.plus.simulation.OsmAndLocationSimulation.nmSpeedFactor = Math.max(0.5f, f / 2f);
		} else if (inflate(simFaster, pad).contains(p.x, p.y)) {
			net.osmand.plus.simulation.OsmAndLocationSimulation.nmSpeedFactor = Math.min(16f, f * 2f);
		} else if (inflate(simSkip, pad).contains(p.x, p.y)) {
			net.osmand.plus.simulation.OsmAndLocationSimulation.nmSkipMeters = 1000f;
		} else if (inflate(simStop, pad).contains(p.x, p.y)) {
			MapActivity a = getMapActivity();
			net.osmand.plus.simulation.OsmAndLocationSimulation sim = app.getLocationProvider().getLocationSimulation();
			if (sim != null && sim.isRouteAnimating()) {
				sim.startStopRouteAnimation(a);
			}
			net.osmand.plus.simulation.OsmAndLocationSimulation.nmSpeedFactor = 1f;
		} else {
			return false;
		}
		if (view != null) {
			view.refreshMap();
		}
		return true;
	}

	// ---------------------------------------------------------------- Garmin-style data bar

	private static boolean nmRoadMode(ApplicationMode m) {
		return m == ApplicationMode.CAR || m == ApplicationMode.TRUCK
				|| m.getParent() == ApplicationMode.CAR || m.getParent() == ApplicationMode.TRUCK;
	}

	private float bottomLimit(int h) {
		MapActivity activity = getMapActivity();
		if (activity != null) {
			View bottom = activity.findViewById(R.id.map_bottom_widgets_panel);
			if (bottom != null && bottom.getVisibility() == View.VISIBLE && bottom.getHeight() > 0) {
				int[] loc = new int[2];
				bottom.getLocationInWindow(loc);
				return loc[1] - 6 * dp;
			}
		}
		return h - 6 * dp;
	}

	private void drawDataBar(Canvas c, int w, int h, RoutingHelper rh, boolean night) {
		// sits right of OsmAnd's speedometer (speed + limit), like the data fields of a truck navigator
		boolean it = italian();
		int cells = 3;
		float bottom = bottomLimit(h);
		float barH = 58 * dp;
		float left = 74 * dp;
		float cellW = Math.min(112 * dp, (w - 116 * dp - left) / cells);
		RectF bar = new RectF(left, bottom - barH, left + cellW * cells, bottom);
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(0x55000000);
		c.drawRoundRect(new RectF(bar.left + 2 * dp, bar.top + 3 * dp, bar.right + 2 * dp, bar.bottom + 3 * dp), 8 * dp, 8 * dp, fill);
		fill.setColor(night ? 0xFF22262B : Color.WHITE);
		c.drawRoundRect(bar, 8 * dp, 8 * dp, fill);
		String[][] data = new String[cells][];
		int leftS = rh.getLeftTime();
		String tt;
		String tu;
		if (leftS >= 3600) {
			tt = (leftS / 3600) + ":" + String.format(Locale.US, "%02d", (leftS % 3600) / 60);
			tu = "h";
		} else {
			tt = String.valueOf(Math.max(0, leftS / 60));
			tu = "min";
		}
		data[0] = new String[]{tt, tu, it ? "Arrivo tra" : "Arrive in"};
		net.osmand.plus.utils.FormattedValue d = OsmAndFormatter.getFormattedDistanceValue(rh.getLeftDistance(), app);
		data[1] = new String[]{d.value, d.unit, it ? "Distanza" : "Distance"};
		String eta = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis() + leftS * 1000L));
		data[2] = new String[]{eta, "", it ? "Arrivo" : "Arrival"};
		for (int i = 0; i < cells; i++) {
			float cx0 = bar.left + i * cellW;
			if (i > 0) {
				stroke.setColor(night ? 0xFF3A4048 : 0xFFD6D9DD);
				stroke.setStrokeWidth(1.2f * dp);
				c.drawLine(cx0, bar.top + 9 * dp, cx0, bar.bottom - 9 * dp, stroke);
			}
			String value = data[i][0];
			String unit = data[i][1];
			float vs = 26 * dp;
			text.setTextSize(vs);
			float us = 12 * dp;
			Paint up = new Paint(text);
			up.setTextAlign(Paint.Align.LEFT);
			up.setTextSize(us);
			float total = text.measureText(value) + (unit.isEmpty() ? 0 : 3 * dp + up.measureText(unit));
			while (total > cellW - 10 * dp && vs > 14 * dp) {
				vs -= dp;
				text.setTextSize(vs);
				total = text.measureText(value) + (unit.isEmpty() ? 0 : 3 * dp + up.measureText(unit));
			}
			float x = cx0 + (cellW - total) / 2f;
			float base = bar.top + 32 * dp;
			text.setTextAlign(Paint.Align.LEFT);
			text.setColor(night ? Color.WHITE : 0xFF15181C);
			c.drawText(value, x, base, text);
			if (!unit.isEmpty()) {
				up.setColor(night ? 0xFFB0B6BE : 0xFF4A5058);
				c.drawText(unit, x + text.measureText(value) + 3 * dp, base, up);
			}
			up.setTextAlign(Paint.Align.CENTER);
			up.setTextSize(11 * dp);
			up.setColor(night ? 0xFF9AA3AD : 0xFF5F6670);
			c.drawText(data[i][2], cx0 + cellW / 2f, bar.bottom - 9 * dp, up);
		}
	}

	// ---------------------------------------------------------------- buttons

	private void drawButtons(Canvas c, int w, int h, boolean landscape, float mapLeft, float mapTop, float mapRight, float mapBottom) {
		float d = 48 * dp;
		float x = mapLeft + 10 * dp;
		float blockH = 2 * d + 10 * dp;
		float minY = mapTop + 6 * dp;
		float maxY = mapBottom - blockH - 56 * dp;
		if (maxY < minY || mapRight - mapLeft < 120 * dp) {
			buttonsVisible = false;
			return;
		}
		float y = Math.min(maxY, Math.max(minY, mapTop + (mapBottom - mapTop) * 0.45f));
		List<RectF> obs = obstacles();
		float found = -1;
		for (float dy = 0; dy < h && found < 0; dy += 8 * dp) {
			for (float cand : new float[]{y + dy, y - dy}) {
				if (cand < minY || cand > maxY) {
					continue;
				}
				RectF block = new RectF(x - 4 * dp, cand - 4 * dp, x + d + 4 * dp, cand + blockH + 4 * dp);
				boolean free = true;
				for (RectF o : obs) {
					if (RectF.intersects(block, o)) {
						free = false;
						break;
					}
				}
				if (free) {
					found = cand;
					break;
				}
			}
		}
		if (found >= 0) {
			y = found;
		}
		reportBtn.set(x, y, x + d, y + d);
		poiBtn.set(x, y + d + 10 * dp, x + d, y + 2 * d + 10 * dp);
		buttonsVisible = true;
		drawRoundButton(c, reportBtn, BANNER_RED, "!", italian() ? "Segnala" : "Report");
		drawRoundButton(c, poiBtn, 0xFF1565C0, "P", "POI");
		long tnow = System.currentTimeMillis();
		if (tnow - lastBtnLog > 3000) {
			lastBtnLog = tnow;
			Log.i(TAG, "NMBTN report " + (int) reportBtn.centerX() + " " + (int) reportBtn.centerY()
					+ " poi " + (int) poiBtn.centerX() + " " + (int) poiBtn.centerY());
		}
		placed.add(new RectF(x, y, x + d, y + blockH));
		drawPoiOverlay(c, x + d + 8 * dp, minY + 4 * dp, mapBottom - 6 * dp, mapRight - 8 * dp);
	}

	private void drawRoundButton(Canvas c, RectF r, int color, String symbol, String label) {
		float rad = r.width() / 2f;
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(0x40000000);
		c.drawCircle(r.centerX(), r.centerY() + 3 * dp, rad, fill);
		fill.setColor(0xFFFFFFFF);
		fill.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, CARD_TOP, CARD_BOTTOM,
				android.graphics.Shader.TileMode.CLAMP));
		c.drawCircle(r.centerX(), r.centerY(), rad, fill);
		fill.setShader(null);
		stroke.setColor(color);
		stroke.setStrokeWidth(2.5f * dp);
		c.drawCircle(r.centerX(), r.centerY(), rad - 1.5f * dp, stroke);
		String key = "!".equals(symbol) ? "rep_warn" : "parking";
		int icon = (int) (rad * 0.95f);
		c.drawBitmap(NavMasterIcons.get(key, icon), r.centerX() - icon / 2f, r.centerY() - icon * 0.72f, bmp);
		text.setTextAlign(Paint.Align.CENTER);
		text.setColor(Color.WHITE);
		text.setTextSize(9 * dp);
		c.drawText(label, r.centerX(), r.bottom - 7 * dp, text);
	}

	@Override
	public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		if (onSimTap(point)) {
			return true;
		}
		if (!buttonsVisible) {
			return false;
		}
		float pad = 6 * dp;
		if (inflate(reportBtn, pad).contains(point.x, point.y)) {
			showReportDialog();
			return true;
		}
		if (inflate(poiBtn, pad).contains(point.x, point.y)) {
			onPoiButton();
			return true;
		}
		if (onPoiListTap(point)) {
			return true;
		}
		return false;
	}

	@Override
	public boolean onLongPressEvent(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		if (buttonsVisible && inflate(poiBtn, 6 * dp).contains(point.x, point.y)) {
			showPoiDialog();
			return true;
		}
		return false;
	}

	private static RectF inflate(RectF r, float pad) {
		return new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
	}

	private static final String[] REPORTS_IT = {"Incidente", "Lavori in corso", "Ostacolo sulla strada", "Veicolo fermo",
			"Coda / traffico", "Autovelox", "Controllo polizia", "Divieto per camion", "Limite altezza / peso", "Strada chiusa"};
	private static final String[] REPORTS_EN = {"Accident", "Road works", "Object on road", "Stopped vehicle",
			"Traffic jam", "Speed camera", "Police check", "No trucks", "Height / weight limit", "Road closed"};
	private static final int[] REPORT_COLORS = {0xFFD32F2F, 0xFFF57C00, 0xFFF9A825, 0xFFF57C00,
			0xFFD32F2F, 0xFF6A1B9A, 0xFF1565C0, 0xFFC62828, 0xFFC62828, 0xFF424242};

	private static final String[] REPORT_KEYS = {"accident", "works", "obstacle", "stopped", "traffic",
			"camera", "police", "no_trucks", "limit", "closed"};

	private int px(float v) {
		return Math.round(v * dp);
	}

	private android.graphics.drawable.Drawable iconDrawable(MapActivity a, String key, int sizeDp) {
		return new android.graphics.drawable.BitmapDrawable(a.getResources(), NavMasterIcons.get(key, px(sizeDp)));
	}

	private android.widget.TextView iconRow(MapActivity a, String key, String label) {
		android.widget.TextView tv = new android.widget.TextView(a);
		tv.setText(label);
		tv.setTextSize(17);
		tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
		tv.setPadding(px(20), px(10), px(20), px(10));
		tv.setCompoundDrawablePadding(px(16));
		tv.setCompoundDrawablesWithIntrinsicBounds(iconDrawable(a, key, 36), null, null, null);
		tv.setMinHeight(px(56));
		android.util.TypedValue tvv = new android.util.TypedValue();
		a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvv, true);
		tv.setBackgroundResource(tvv.resourceId);
		return tv;
	}

	private void showReportDialog() {
		MapActivity a = getMapActivity();
		Location me = app.getLocationProvider().getLastKnownLocation();
		if (a == null) {
			return;
		}
		if (me == null) {
			Toast.makeText(a, italian() ? "Posizione GPS non disponibile" : "No GPS position", Toast.LENGTH_SHORT).show();
			return;
		}
		final String[] items = italian() ? REPORTS_IT : REPORTS_EN;
		android.widget.LinearLayout list = new android.widget.LinearLayout(a);
		list.setOrientation(android.widget.LinearLayout.VERTICAL);
		android.widget.ScrollView sv = new android.widget.ScrollView(a);
		sv.addView(list);
		AlertDialog dlg = new AlertDialog.Builder(a)
				.setTitle(italian() ? "Segnala sulla strada" : "Report on the road")
				.setView(sv)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		for (int i = 0; i < items.length; i++) {
			final int which = i;
			android.widget.TextView row = iconRow(a, "rep_" + REPORT_KEYS[i], items[i]);
			row.setOnClickListener(v -> {
				dlg.dismiss();
				saveReport(a, me, items[which], REPORT_COLORS[which]);
			});
			list.addView(row);
		}
		dlg.show();
	}

	private void saveReport(MapActivity a, Location me, String type, int color) {
		try {
			String time = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date());
			FavouritePoint p = new FavouritePoint(me.getLatitude(), me.getLongitude(), type + " " + time, REPORT_CATEGORY);
			p.setColor(color);
			p.setDescription((italian() ? "Segnalazione NavMaster: " : "NavMaster report: ") + type);
			p.setTimestamp(System.currentTimeMillis());
			boolean ok = app.getFavoritesHelper().addFavourite(p);
			Toast.makeText(a, ok ? (italian() ? "Segnalazione salvata: " : "Report saved: ") + type
					: (italian() ? "Segnalazione non salvata" : "Report not saved"), Toast.LENGTH_SHORT).show();
			if (view != null) {
				view.refreshMap();
			}
		} catch (Exception e) {
			Log.e(TAG, "report failed", e);
		}
	}

	// ---------------------------------------------------------------- POIs along the route

	private static final String[] POI_IDS = {"fuel", "services", "rest_area", "parking", "car_repair",
			"charging_station", "restaurant", "hotel", "toilets", "car_wash"};
	private static final String[] POI_IT = {"Carburante", "Aree di servizio", "Aree di sosta", "Parcheggi", "Officine",
			"Ricarica elettrica", "Ristoranti", "Hotel", "Servizi igienici", "Autolavaggi"};
	private static final String[] POI_EN = {"Fuel", "Service areas", "Rest areas", "Parking", "Repair shops",
			"Charging", "Restaurants", "Hotels", "Toilets", "Car wash"};
	private static final int[] POI_SECONDS = {5, 10, 20, 30};
	private static final int POI_MAX_ROWS = 5;

	private net.osmand.plus.settings.backend.preferences.CommonPreference<Boolean> poiAlwaysPref;
	private net.osmand.plus.settings.backend.preferences.CommonPreference<Integer> poiSecondsPref;
	private net.osmand.plus.settings.backend.preferences.CommonPreference<String> poiCatsPref;
	private long poiShowUntil;
	private long poiHideUntil;
	private final RectF poiBox = new RectF();
	private final List<RectF> poiRowRects = new java.util.ArrayList<>();
	private final List<NmPoi> poiShown = new java.util.ArrayList<>();
	private final List<NmPoi> routePois = new java.util.ArrayList<>();
	private String poiRouteKey;
	private volatile boolean poiSearching;

	private static class NmPoi {
		String cat;
		String name;
		double lat;
		double lon;
		int routeIndex;
		int dist;
	}

	private void ensurePoiPrefs() {
		if (poiAlwaysPref == null) {
			poiAlwaysPref = app.getSettings().registerBooleanPreference("nm_poi_overlay_always", true).makeGlobal();
			poiSecondsPref = app.getSettings().registerIntPreference("nm_poi_overlay_seconds", 10).makeGlobal();
			poiCatsPref = app.getSettings().registerStringPreference("nm_poi_categories", "fuel,parking,rest_area").makeGlobal();
		}
	}

	private java.util.Set<String> selectedCats() {
		ensurePoiPrefs();
		java.util.Set<String> set = new java.util.LinkedHashSet<>();
		String v = poiCatsPref.get();
		if (!Algorithms.isEmpty(v)) {
			for (String c : v.split(",")) {
				if (!c.trim().isEmpty()) {
					set.add(c.trim());
				}
			}
		}
		return set;
	}

	private String catLabel(String id) {
		for (int i = 0; i < POI_IDS.length; i++) {
			if (POI_IDS[i].equals(id)) {
				return italian() ? POI_IT[i] : POI_EN[i];
			}
		}
		return id;
	}

	private boolean poiOverlayVisible() {
		long now = System.currentTimeMillis();
		return now > poiHideUntil && (poiAlwaysPref.get() || now < poiShowUntil);
	}

	// tap on the POI button: show / hide the list; two taps in a row (or the list header) open the settings
	private void onPoiButton() {
		ensurePoiPrefs();
		long now = System.currentTimeMillis();
		boolean second = now - lastPoiBtnTap < 2500;
		lastPoiBtnTap = now;
		if (second) {
			showPoiDialog();
		} else if (selectedCats().isEmpty()) {
			showPoiDialog();
		} else if (poiOverlayVisible()) {
			poiHideUntil = now + 10 * 60 * 1000L;
			poiShowUntil = 0;
			if (view != null) {
				view.refreshMap();
			}
		} else {
			poiHideUntil = 0;
			poiShowUntil = now + poiSecondsPref.get() * 1000L;
			if (view != null) {
				view.refreshMap();
				view.getView().postDelayed(() -> view.refreshMap(), poiSecondsPref.get() * 1000L + 200);
			}
		}
	}

	private void showPoiDialog() {
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		ensurePoiPrefs();
		boolean it = italian();
		final String[] names = it ? POI_IT : POI_EN;
		java.util.Set<String> sel = selectedCats();
		android.widget.LinearLayout root = new android.widget.LinearLayout(a);
		root.setOrientation(android.widget.LinearLayout.VERTICAL);
		root.setPadding(0, px(4), 0, px(8));
		final android.widget.CheckBox[] boxes = new android.widget.CheckBox[POI_IDS.length];
		for (int i = 0; i < POI_IDS.length; i++) {
			android.widget.CheckBox cb = new android.widget.CheckBox(a);
			cb.setText(names[i]);
			cb.setTextSize(17);
			cb.setChecked(sel.contains(POI_IDS[i]));
			cb.setPadding(px(12), px(6), px(8), px(6));
			cb.setMinHeight(px(52));
			cb.setCompoundDrawablePadding(px(12));
			cb.setCompoundDrawablesWithIntrinsicBounds(null, null, iconDrawable(a, POI_IDS[i], 34), null);
			android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
					android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.setMargins(px(16), 0, px(20), 0);
			root.addView(cb, lp);
			boxes[i] = cb;
		}
		android.widget.TextView modeTitle = new android.widget.TextView(a);
		modeTitle.setText(it ? "Elenco POI in sovraimpressione" : "POI list on the map");
		modeTitle.setTextSize(15);
		modeTitle.setTypeface(Typeface.DEFAULT_BOLD);
		modeTitle.setPadding(px(24), px(16), px(24), px(4));
		root.addView(modeTitle);
		android.widget.RadioGroup mode = new android.widget.RadioGroup(a);
		mode.setPadding(px(20), 0, px(20), 0);
		android.widget.RadioButton always = new android.widget.RadioButton(a);
		always.setId(View.generateViewId());
		always.setText(it ? "Mostra sempre" : "Always show");
		android.widget.RadioButton onDemand = new android.widget.RadioButton(a);
		onDemand.setId(View.generateViewId());
		onDemand.setText(it ? "Mostra solo quando tocco «POI»" : "Show only when I tap «POI»");
		mode.addView(always);
		mode.addView(onDemand);
		mode.check(poiAlwaysPref.get() ? always.getId() : onDemand.getId());
		root.addView(mode);
		android.widget.TextView secTitle = new android.widget.TextView(a);
		secTitle.setText(it ? "Durata quando richiesto" : "Duration when requested");
		secTitle.setTextSize(14);
		secTitle.setPadding(px(24), px(8), px(24), 0);
		root.addView(secTitle);
		android.widget.RadioGroup secs = new android.widget.RadioGroup(a);
		secs.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		secs.setPadding(px(20), 0, px(20), 0);
		int checkedSec = -1;
		for (int sec : POI_SECONDS) {
			android.widget.RadioButton rb = new android.widget.RadioButton(a);
			rb.setId(View.generateViewId());
			rb.setText(sec + " s");
			rb.setTag(sec);
			secs.addView(rb);
			if (sec == poiSecondsPref.get()) {
				checkedSec = rb.getId();
			}
		}
		if (checkedSec != -1) {
			secs.check(checkedSec);
		}
		root.addView(secs);
		android.widget.ScrollView sv = new android.widget.ScrollView(a);
		sv.addView(root);
		new AlertDialog.Builder(a)
				.setTitle(it ? "POI lungo il percorso" : "POIs along the route")
				.setView(sv)
				.setPositiveButton(android.R.string.ok, (dlg, which) -> {
					StringBuilder cats = new StringBuilder();
					for (int i = 0; i < POI_IDS.length; i++) {
						if (boxes[i].isChecked()) {
							cats.append(cats.length() > 0 ? "," : "").append(POI_IDS[i]);
						}
					}
					poiCatsPref.set(cats.toString());
					poiAlwaysPref.set(mode.getCheckedRadioButtonId() == always.getId());
					View chosen = secs.findViewById(secs.getCheckedRadioButtonId());
					if (chosen != null && chosen.getTag() instanceof Integer) {
						poiSecondsPref.set((Integer) chosen.getTag());
					}
					poiRouteKey = null;
					poiHideUntil = 0;
					poiShowUntil = System.currentTimeMillis() + poiSecondsPref.get() * 1000L;
					if (view != null) {
						view.refreshMap();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// searches the chosen categories along the whole route once per route (in the background);
	// nothing is added to the map layers and nothing is announced by voice
	private void refreshRoutePois(RouteCalculationResult route) {
		java.util.Set<String> cats = selectedCats();
		String key = System.identityHashCode(route) + "|" + cats;
		if (key.equals(poiRouteKey) || poiSearching) {
			return;
		}
		poiRouteKey = key;
		poiSearching = true;
		final List<Location> locs = new java.util.ArrayList<>(route.getImmutableAllLocations());
		executor.execute(() -> {
			List<NmPoi> res = new java.util.ArrayList<>();
			try {
				java.util.Map<Location, Integer> index = new java.util.IdentityHashMap<>();
				for (int i = 0; i < locs.size(); i++) {
					index.put(locs.get(i), i);
				}
				String lang = app.getSettings().MAP_PREFERRED_LOCALE.get();
				for (String cat : cats) {
					PoiUIFilter f = app.getPoiFilters().getFilterById(PoiUIFilter.STD_PREFIX + cat);
					if (f == null) {
						continue;
					}
					List<net.osmand.data.Amenity> list = f.searchAmenitiesOnThePath(locs, 250);
					for (net.osmand.data.Amenity am : list) {
						net.osmand.data.Amenity.AmenityRoutePoint rp = am.getRoutePoint();
						Integer idx = rp != null ? index.get(rp.pointA) : null;
						if (idx == null) {
							continue;
						}
						NmPoi p = new NmPoi();
						p.cat = cat;
						String n = am.getName(lang);
						p.name = Algorithms.isEmpty(n) ? catLabel(cat) : n;
						p.lat = am.getLocation().getLatitude();
						p.lon = am.getLocation().getLongitude();
						p.routeIndex = idx;
						res.add(p);
					}
				}
			} catch (Throwable e) {
				Log.w(TAG, "route pois: " + e);
			}
			synchronized (routePois) {
				routePois.clear();
				routePois.addAll(res);
			}
			poiSearching = false;
			Log.i(TAG, "route pois found: " + res.size());
			if (view != null) {
				view.refreshMap();
			}
		});
	}

	private List<NmPoi> nextPois(RouteCalculationResult route, int max) {
		List<NmPoi> out = new java.util.ArrayList<>();
		int cur = route.getCurrentRoute();
		synchronized (routePois) {
			for (NmPoi p : routePois) {
				if (p.routeIndex <= cur) {
					continue;
				}
				p.dist = route.getDistanceToPoint(p.routeIndex);
				if (p.dist > 0) {
					out.add(p);
				}
			}
		}
		java.util.Collections.sort(out, (x, y) -> Integer.compare(x.dist, y.dist));
		return out.size() > max ? new java.util.ArrayList<>(out.subList(0, max)) : out;
	}

	// list of the nearest POIs ahead (all chosen categories mixed), tap a row to navigate there
	private void drawPoiOverlay(Canvas c, float x, float top, float maxBottom, float maxRight) {
		ensurePoiPrefs();
		poiRowRects.clear();
		poiShown.clear();
		RouteCalculationResult route = app.getRoutingHelper().getRoute();
		if (!poiOverlayVisible() || selectedCats().isEmpty() || route == null) {
			poiBox.setEmpty();
			return;
		}
		refreshRoutePois(route);
		float rowH = 32 * dp;
		float headH = 18 * dp;
		float w = Math.min(182 * dp, maxRight - x);
		if (w < 120 * dp) {
			poiBox.setEmpty();
			return;
		}
		RectF want = new RectF(x, top, x + w, top + headH + POI_MAX_ROWS * rowH + 8 * dp);
		want.bottom = Math.min(want.bottom, maxBottom);
		RectF fit = nmFit(want, obstacles(), 120 * dp, headH + rowH + 8 * dp, 8 * dp);
		if (fit == null) {
			poiBox.setEmpty();
			return;
		}
		int maxRows = Math.max(1, Math.min(POI_MAX_ROWS, (int) ((fit.height() - headH - 8 * dp) / rowH)));
		List<NmPoi> pois = nextPois(route, maxRows);
		int rows = Math.max(1, pois.size());
		poiBox.set(fit.left, fit.top, fit.right, fit.top + headH + rows * rowH + 8 * dp);
		card(c, poiBox, 14 * dp);
		placed.add(new RectF(poiBox));
		caption(c, italian() ? "lungo il percorso" : "along the route", poiBox.left + 10 * dp, poiBox.top + 13 * dp, Paint.Align.LEFT);
		if (pois.isEmpty()) {
			text.setTextAlign(Paint.Align.LEFT);
			text.setTextSize(11.5f * dp);
			text.setColor(0xFFB8C0CA);
			c.drawText(poiSearching ? (italian() ? "Cerco…" : "Searching…") : (italian() ? "Nessun POI vicino" : "No POIs ahead"),
					poiBox.left + 10 * dp, poiBox.top + headH + rowH / 2f + 4 * dp, text);
			return;
		}
		for (int i = 0; i < pois.size(); i++) {
			NmPoi p = pois.get(i);
			float y = poiBox.top + headH + i * rowH;
			RectF row = new RectF(poiBox.left, y, poiBox.right, y + rowH);
			poiRowRects.add(row);
			poiShown.add(p);
			if (i > 0) {
				stroke.setColor(0x14FFFFFF);
				stroke.setStrokeWidth(1f * dp);
				c.drawLine(row.left + 12 * dp, row.top, row.right - 12 * dp, row.top, stroke);
			}
			int icon = (int) (22 * dp);
			c.drawBitmap(NavMasterIcons.get(p.cat, icon), row.left + 8 * dp, row.centerY() - icon / 2f, bmp);
			net.osmand.plus.utils.FormattedValue fv = OsmAndFormatter.getFormattedDistanceValue(p.dist, app);
			text.setTextSize(13 * dp);
			Paint up = new Paint(text);
			up.setTextSize(13 * dp * 0.45f);
			float dw = text.measureText(fv.value) + up.measureText(fv.unit) + 2 * dp;
			drawValue(c, fv.value, fv.unit, row.right - 8 * dp, row.centerY() + 5 * dp, 13 * dp, Color.WHITE, Paint.Align.RIGHT);
			float nameX = row.left + 8 * dp + icon + 8 * dp;
			float nameMax = row.right - 8 * dp - dw - 6 * dp - nameX;
			text.setTextAlign(Paint.Align.LEFT);
			text.setTextSize(11.5f * dp);
			text.setColor(0xFFDDE3EA);
			String n = p.name;
			if (text.measureText(n) > nameMax) {
				while (n.length() > 2 && text.measureText(n + "…") > nameMax) {
					n = n.substring(0, n.length() - 1);
				}
				n = n + "…";
			}
			c.drawText(n, nameX, row.centerY() + 4 * dp, text);
		}
	}

	private boolean onPoiListTap(PointF pt) {
		if (poiBox.isEmpty() || !poiBox.contains(pt.x, pt.y)) {
			return false;
		}
		for (int i = 0; i < poiRowRects.size(); i++) {
			if (poiRowRects.get(i).contains(pt.x, pt.y)) {
				showPoiActions(poiShown.get(i));
				return true;
			}
		}
		showPoiDialog();
		return true;
	}

	// ask whether to go there: next stop (intermediate point) or new destination
	private void showPoiActions(NmPoi p) {
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		boolean it = italian();
		String dist = OsmAndFormatter.getFormattedDistance(p.dist, app);
		android.widget.TextView msg = iconRow(a, p.cat, catLabel(p.cat) + "\n" + (it ? "tra " : "in ") + dist
				+ (it ? " lungo il percorso" : " along the route"));
		msg.setBackground(null);
		net.osmand.data.LatLon ll = new net.osmand.data.LatLon(p.lat, p.lon);
		net.osmand.data.PointDescription pd = new net.osmand.data.PointDescription(net.osmand.data.PointDescription.POINT_TYPE_POI, p.name);
		new AlertDialog.Builder(a)
				.setTitle(p.name)
				.setView(msg)
				.setPositiveButton(it ? "Aggiungi come tappa" : "Add as next stop", (d, w) -> {
					app.getTargetPointsHelper().navigateToPoint(ll, true, 0, pd);
					Toast.makeText(a, (it ? "Tappa aggiunta: " : "Stop added: ") + p.name, Toast.LENGTH_SHORT).show();
				})
				.setNeutralButton(it ? "Nuova destinazione" : "New destination", (d, w) -> {
					app.getTargetPointsHelper().navigateToPoint(ll, true, -1, pd);
					Toast.makeText(a, (it ? "Nuova destinazione: " : "New destination: ") + p.name, Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}
}
