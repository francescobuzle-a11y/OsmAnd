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
	private float lastPanelH;

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
			if (!navigating || !road) {
				applyHudBottom(0);
				if (!navigating) {
					return;
				}
			}
			if (road) {
				float panelH = drawSygicPanel(canvas, w, h, landscape, rh, night);
				lastPanelH = panelH;
				applyHudBottom((int) panelH);
			}
			float top = topOffset(h);
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

			float panelBottom = 0;
			if (System.currentTimeMillis() - JunctionViewLayer.nmPanelShownAt < 800) {
				panelBottom = JunctionViewLayer.nmPanelBottom;
			}

			// restriction banner
			updateRestriction(rh, demo);
			float bannerTop = landscape ? top : top + 64 * dp;
			if (!landscape && System.currentTimeMillis() - JunctionViewLayer.nmPanelShownAt < 800) {
				bannerTop = JunctionViewLayer.nmPanelBottom + 6 * dp;
			}
			if (restrictionText != null) {
				RectF banner = landscape
						? new RectF(w * 0.11f, bannerTop, w * 0.49f, bannerTop + 40 * dp)
						: new RectF(64 * dp, bannerTop, w - 10 * dp, bannerTop + 40 * dp);
				drawBanner(canvas, banner);
				bannerTop = banner.bottom + 6 * dp;
				panelBottom = Math.max(panelBottom, banner.bottom);
			}

			// arrival panel with satellite view (junction view has priority)
			boolean junctionShown = System.currentTimeMillis() - JunctionViewLayer.nmPanelShownAt < 800;
			boolean arrivalDemo = demo != null && demo.contains("NAVMASTER_ARRIVO");
			if (!junctionShown && (arrivalDemo || rh.getLeftDistance() <= ARRIVAL_SHOW_M)) {
				TargetPoint tp = app.getTargetPointsHelper().getPointToNavigate();
				if (tp != null) {
					RectF panel;
					if (landscape) {
						panel = new RectF(w * 0.50f, top, w - 70 * dp, Math.min(h - lastPanelH - 50 * dp, top + (w * 0.5f) * 0.70f));
					} else {
						float t = Math.max(bannerTop, top + 64 * dp);
						panel = new RectF(10 * dp, t, w - 10 * dp, t + Math.min(w * 0.62f, h * 0.34f));
					}
					drawArrival(canvas, panel, tp.getLatitude(), tp.getLongitude(), rh, night);
					panelBottom = Math.max(panelBottom, panel.bottom);
				}
			}
			drawButtons(canvas, w, h, landscape, panelBottom);
			long now = System.currentTimeMillis();
			if (now - lastLog > 5000) {
				lastLog = now;
				Log.i(TAG, "draw restriction=" + restrictionText + " left=" + rh.getLeftDistance() + " demo=" + demo);
			}
		} catch (Throwable e) {
			Log.e(TAG, "draw failed", e);
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
			restrictionText = restrictionText + "  ·  "
					+ OsmAndFormatter.getFormattedDistance(restrictionDist, app);
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

	// keeps OsmAnd's map buttons and widgets above the NavMaster bottom panel
	private void applyHudBottom(int panelPx) {
		if (panelPx == hudBottomApplied) {
			return;
		}
		hudBottomApplied = panelPx;
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		a.runOnUiThread(() -> {
			try {
				net.osmand.plus.views.controls.MapHudLayout hud =
						app.getOsmandMap().getMapLayers().getMapControlsLayer().getMapHudLayout();
				if (hud == null) {
					return;
				}
				if (panelPx <= 0) {
					hud.clearExternalVisibleArea();
				} else {
					int[] loc = new int[2];
					hud.getLocationOnScreen(loc);
					boolean ok = hud.setExternalVisibleArea(new android.graphics.Rect(loc[0], loc[1],
							loc[0] + hud.getWidth(), loc[1] + hud.getHeight() - panelPx));
					if (!ok && hud.getWidth() <= 0) {
						hudBottomApplied = -1;
					}
				}
			} catch (Throwable e) {
				Log.w(TAG, "hud area: " + e);
			}
		});
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

	private float drawSygicPanel(Canvas c, int w, int h, boolean landscape, RoutingHelper rh, boolean night) {
		boolean it = italian();
		net.osmand.plus.simulation.OsmAndLocationSimulation sim = app.getLocationProvider().getLocationSimulation();
		boolean simOn = sim != null && sim.isRouteAnimating();
		float margin = 10 * dp;
		float cardH = (landscape ? 92 : 118) * dp;
		float simH = simOn ? 52 * dp : 0;
		RectF card = new RectF(margin, h - margin - cardH - simH, w - margin, h - margin);
		float radius = 22 * dp;
		card(c, card, radius);
		float top = card.top + simH;

		// route progress: thin rounded bar with turn dots and the next limit
		float barY = top + 10 * dp;
		float bx0 = card.left + 18 * dp;
		float bx1 = card.right - 18 * dp;
		stroke.setStrokeCap(Paint.Cap.ROUND);
		stroke.setStrokeWidth(4 * dp);
		stroke.setColor(0x33FFFFFF);
		c.drawLine(bx0, barY, bx1, barY, stroke);
		stroke.setShader(new android.graphics.LinearGradient(bx0, 0, bx1, 0, ACCENT, ACCENT_2, android.graphics.Shader.TileMode.CLAMP));
		c.drawLine(bx0, barY, bx1, barY, stroke);
		stroke.setShader(null);
		int left = rh.getLeftDistance();
		RouteCalculationResult route = rh.getRoute();
		if (route != null && left > 0) {
			List<net.osmand.plus.routing.RouteDirectionInfo> dirs = rh.getRouteDirections();
			if (dirs != null) {
				fill.setColor(Color.WHITE);
				for (net.osmand.plus.routing.RouteDirectionInfo di : dirs) {
					int toEnd = route.getDistanceFromPoint(di.routePointOffset);
					if (toEnd <= 0 || toEnd >= left) {
						continue;
					}
					float x = bx0 + (1f - toEnd / (float) left) * (bx1 - bx0);
					c.drawCircle(x, barY, 2.6f * dp, fill);
				}
			}
			if (restrictionText != null && restrictionDist < left) {
				float x = bx0 + restrictionDist / (float) left * (bx1 - bx0);
				fill.setColor(Color.WHITE);
				c.drawCircle(x, barY, 5.5f * dp, fill);
				fill.setColor(BANNER_RED);
				c.drawCircle(x, barY, 4f * dp, fill);
			}
		}
		// vehicle marker at the start of the bar
		fill.setColor(Color.WHITE);
		c.drawCircle(bx0, barY, 5 * dp, fill);
		fill.setColor(ACCENT);
		c.drawCircle(bx0, barY, 3.2f * dp, fill);

		float contentTop = barY + 8 * dp;
		float contentH = card.bottom - contentTop - 6 * dp;
		float colW = landscape ? Math.min(w * 0.2f, 180 * dp) : (card.width()) * 0.25f;
		int leftS = rh.getLeftTime();
		String eta = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis() + leftS * 1000L));
		net.osmand.plus.utils.FormattedValue dist = OsmAndFormatter.getFormattedDistanceValue(left, app);
		Location me = app.getLocationProvider().getLastKnownLocation();
		float mps = me != null && me.hasSpeed() ? me.getSpeed() : 0;
		net.osmand.plus.utils.FormattedValue sp = OsmAndFormatter.getFormattedSpeedValue(mps, app);
		float limit = rh.getCurrentMaxSpeed();
		boolean speeding = limit > 0 && mps > limit + 1.5f;
		String[] tl = timeLeft(leftS);
		float big = (landscape ? 25 : 24) * dp;
		float lx = card.left + 16 * dp;
		float rx = card.right - 16 * dp;
		float row1 = contentTop + contentH * 0.30f;
		float row2 = contentTop + contentH * 0.78f;
		// left: arrival time + remaining distance
		drawValue(c, eta, "", lx, row1 + big * 0.36f, big, Color.WHITE, Paint.Align.LEFT);
		caption(c, it ? "arrivo" : "arrival", lx, row1 - big * 0.62f, Paint.Align.LEFT);
		drawValue(c, dist.value, dist.unit, lx, row2 + big * 0.3f, big * 0.8f, 0xFFDDE3EA, Paint.Align.LEFT);
		// right: speed (pill turns red when above the limit) + time left
		text.setTextSize(big);
		Paint up = new Paint(text);
		up.setTextSize(big * 0.45f);
		float spW = text.measureText(sp.value) + up.measureText(sp.unit) + 2 * dp;
		RectF pill = new RectF(rx - spW - 14 * dp, row1 - big * 0.62f, rx + 6 * dp, row1 + big * 0.62f);
		if (speeding) {
			fill.setColor(0xFFE53935);
			c.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, fill);
		}
		drawValue(c, sp.value, sp.unit, rx - 4 * dp, row1 + big * 0.36f, big, Color.WHITE, Paint.Align.RIGHT);
		drawValue(c, tl[0], tl[1], rx, row2 + big * 0.3f, big * 0.8f, 0xFFDDE3EA, Paint.Align.RIGHT);

		// centre: manoeuvre tile + distance + street
		float cx = card.centerX();
		net.osmand.plus.routing.NextDirectionInfo next = rh.getNextRouteDirectionInfo(new net.osmand.plus.routing.NextDirectionInfo(), true);
		String street = null;
		try {
			net.osmand.plus.routing.CurrentStreetName sn = rh.getCurrentName(
					next != null ? next : new net.osmand.plus.routing.NextDirectionInfo(), false);
			street = sn != null ? sn.text : null;
		} catch (Throwable e) {
			// optional
		}
		boolean hasStreet = !Algorithms.isEmpty(street);
		float centreW = card.width() - 2 * colW;
		if (next != null && next.directionInfo != null && next.directionInfo.getTurnType() != null) {
			float tile = Math.min(contentH * (hasStreet ? 0.66f : 0.8f), 60 * dp);
			MapActivity a = getMapActivity();
			if (a != null) {
				int size = (int) (tile * 0.86f);
				if (turnDrawable == null || turnDrawableSize != size) {
					turnDrawable = new net.osmand.plus.views.mapwidgets.TurnDrawable(a, false);
					turnDrawable.setBounds(0, 0, size, size);
					turnDrawableSize = size;
					turnDrawable.setRouteDirectionColor(android.R.color.white);
					turnDrawable.updateColors(true);
				}
				turnDrawable.setTurnType(next.directionInfo.getTurnType());
				net.osmand.plus.utils.FormattedValue nd = OsmAndFormatter.getFormattedDistanceValue(next.distanceTo, app);
				float valueSize = (landscape ? 38 : 34) * dp;
				text.setTextSize(valueSize);
				float vw = text.measureText(nd.value);
				Paint u2 = new Paint(text);
				u2.setTextSize(valueSize * 0.42f);
				float uw = u2.measureText(nd.unit) + 3 * dp;
				float total = tile + 12 * dp + vw + uw;
				float x0 = cx - total / 2f;
				float tileTop = contentTop + (hasStreet ? 2 * dp : (contentH - tile) / 2f);
				RectF t = new RectF(x0, tileTop, x0 + tile, tileTop + tile);
				fill.setShader(new android.graphics.LinearGradient(0, t.top, 0, t.bottom, ACCENT, 0xFF1E8E47,
						android.graphics.Shader.TileMode.CLAMP));
				c.drawRoundRect(t, 14 * dp, 14 * dp, fill);
				fill.setShader(null);
				c.save();
				c.translate(t.centerX() - size / 2f, t.centerY() - size / 2f);
				turnDrawable.draw(c);
				c.restore();
				drawValue(c, nd.value, nd.unit, t.right + 12 * dp, t.centerY() + valueSize * 0.35f, valueSize, Color.WHITE, Paint.Align.LEFT);
			}
		}
		if (hasStreet) {
			text.setTextSize(14 * dp);
			text.setTextAlign(Paint.Align.CENTER);
			text.setColor(0xFFDDE3EA);
			String st = street;
			float maxW = centreW - 12 * dp;
			if (text.measureText(st) > maxW) {
				while (st.length() > 3 && text.measureText(st + "…") > maxW) {
					st = st.substring(0, st.length() - 1);
				}
				st = st + "…";
			}
			c.drawText(st, cx, card.bottom - 12 * dp, text);
		}
		// thin separators
		stroke.setColor(0x1FFFFFFF);
		stroke.setStrokeWidth(1f * dp);
		stroke.setStrokeCap(Paint.Cap.BUTT);
		c.drawLine(card.left + colW, contentTop + 6 * dp, card.left + colW, card.bottom - 12 * dp, stroke);
		c.drawLine(card.right - colW, contentTop + 6 * dp, card.right - colW, card.bottom - 12 * dp, stroke);

		if (simOn) {
			drawSimControls(c, new RectF(card.left, card.top, card.right, card.top + simH));
		} else {
			simVisible = false;
		}
		return h - card.top + 6 * dp;
	}

	// simulation controls row inside the card: slower / speed / faster / skip 1 km / stop
	private void drawSimControls(Canvas c, RectF row) {
		simVisible = true;
		boolean it = italian();
		float d = 38 * dp;
		float gap = 10 * dp;
		float labelW = 64 * dp;
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
		caption(c, it ? "simulazione" : "simulation", label.centerX(), label.bottom - 1 * dp, Paint.Align.CENTER);
		stroke.setColor(0x1FFFFFFF);
		stroke.setStrokeWidth(1f * dp);
		c.drawLine(row.left + 18 * dp, row.bottom, row.right - 18 * dp, row.bottom, stroke);
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

	private void drawButtons(Canvas c, int w, int h, boolean landscape, float panelBottom) {
		float d = 54 * dp;
		float x = 12 * dp;
		float y = landscape ? h * 0.36f : Math.max(h * 0.44f, panelBottom + 14 * dp);
		reportBtn.set(x, y, x + d, y + d);
		poiBtn.set(x, y + d + 12 * dp, x + d, y + 2 * d + 12 * dp);
		buttonsVisible = true;
		drawRoundButton(c, reportBtn, BANNER_RED, "!", italian() ? "Segnala" : "Report");
		drawRoundButton(c, poiBtn, 0xFF1565C0, "P", "POI");
		drawPoiOverlay(c, x + d + 10 * dp, y, h - lastPanelH - 50 * dp);
	}

	private void drawRoundButton(Canvas c, RectF r, int color, String symbol, String label) {
		float rad = r.width() / 2f;
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(0x40000000);
		c.drawCircle(r.centerX(), r.centerY() + 3 * dp, rad, fill);
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
		text.setTextSize(9.5f * dp);
		c.drawText(label, r.centerX(), r.bottom - 8 * dp, text);
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
		if (!poiBox.isEmpty() && poiBox.contains(point.x, point.y)) {
			showPoiDialog();
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

	private net.osmand.plus.settings.backend.preferences.CommonPreference<Boolean> poiAlwaysPref;
	private net.osmand.plus.settings.backend.preferences.CommonPreference<Integer> poiSecondsPref;
	private long poiShowUntil;
	private final RectF poiBox = new RectF();
	private long lastPoiCalc;
	private final String[] poiNearestKey = new String[POI_IDS.length];
	private final int[] poiNearestDist = new int[POI_IDS.length];
	private int poiRows;

	private void ensurePoiPrefs() {
		if (poiAlwaysPref == null) {
			poiAlwaysPref = app.getSettings().registerBooleanPreference("nm_poi_overlay_always", true).makeGlobal();
			poiSecondsPref = app.getSettings().registerIntPreference("nm_poi_overlay_seconds", 10).makeGlobal();
		}
	}

	private boolean isPoiSelected(String id) {
		return app.getPoiFilters().isPoiFilterSelected(PoiUIFilter.STD_PREFIX + id);
	}

	private void onPoiButton() {
		ensurePoiPrefs();
		boolean anySelected = false;
		for (String id : POI_IDS) {
			anySelected |= isPoiSelected(id);
		}
		boolean overlayVisible = poiAlwaysPref.get() || System.currentTimeMillis() < poiShowUntil;
		if (!anySelected || overlayVisible) {
			showPoiDialog();
		} else {
			poiShowUntil = System.currentTimeMillis() + poiSecondsPref.get() * 1000L;
			lastPoiCalc = 0;
			if (view != null) {
				view.refreshMap();
				// redraw once more when the overlay expires
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
		android.widget.LinearLayout root = new android.widget.LinearLayout(a);
		root.setOrientation(android.widget.LinearLayout.VERTICAL);
		root.setPadding(0, px(4), 0, px(8));
		final android.widget.CheckBox[] boxes = new android.widget.CheckBox[POI_IDS.length];
		for (int i = 0; i < POI_IDS.length; i++) {
			android.widget.CheckBox cb = new android.widget.CheckBox(a);
			cb.setText(names[i]);
			cb.setTextSize(17);
			cb.setChecked(isPoiSelected(POI_IDS[i]));
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
		modeTitle.setText(it ? "Riquadro POI in sovraimpressione" : "POI overlay on the map");
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
					for (int i = 0; i < POI_IDS.length; i++) {
						PoiUIFilter f = app.getPoiFilters().getFilterById(PoiUIFilter.STD_PREFIX + POI_IDS[i]);
						if (f == null) {
							continue;
						}
						boolean sel = app.getPoiFilters().isPoiFilterSelected(f);
						if (boxes[i].isChecked() && !sel) {
							app.getPoiFilters().addSelectedPoiFilter(f);
						} else if (!boxes[i].isChecked() && sel) {
							app.getPoiFilters().removeSelectedPoiFilter(f);
						}
					}
					poiAlwaysPref.set(mode.getCheckedRadioButtonId() == always.getId());
					View chosen = secs.findViewById(secs.getCheckedRadioButtonId());
					if (chosen != null && chosen.getTag() instanceof Integer) {
						poiSecondsPref.set((Integer) chosen.getTag());
					}
					enablePoisAlongRoute();
					poiShowUntil = System.currentTimeMillis() + poiSecondsPref.get() * 1000L;
					lastPoiCalc = 0;
					if (view != null) {
						view.refreshMap();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// asks OsmAnd to look for the selected POI types along the route (within 300 m of it)
	private void enablePoisAlongRoute() {
		try {
			ApplicationMode mode = app.getSettings().getApplicationMode();
			((net.osmand.plus.settings.backend.preferences.CommonPreference<Boolean>) app.getSettings().SHOW_NEARBY_POI)
					.setModeValue(mode, true);
			net.osmand.plus.helpers.WaypointHelper wh = app.getWaypointHelper();
			wh.setSearchDeviationRadius(net.osmand.plus.helpers.WaypointHelper.POI, 300);
			wh.recalculatePointsAsync(net.osmand.plus.helpers.WaypointHelper.POI, null);
		} catch (Throwable e) {
			Log.w(TAG, "poi along route: " + e);
		}
	}

	private void updateNearestPois() {
		long now = System.currentTimeMillis();
		if (now - lastPoiCalc < 1500) {
			return;
		}
		lastPoiCalc = now;
		poiRows = 0;
		java.util.Arrays.fill(poiNearestDist, Integer.MAX_VALUE);
		try {
			net.osmand.plus.helpers.WaypointHelper wh = app.getWaypointHelper();
			if (!wh.isTypeEnabled(net.osmand.plus.helpers.WaypointHelper.POI)) {
				enablePoisAlongRoute();
				return;
			}
			List<net.osmand.plus.helpers.LocationPointWrapper> pts = wh.getWaypoints(net.osmand.plus.helpers.WaypointHelper.POI);
			for (net.osmand.plus.helpers.LocationPointWrapper w : new java.util.ArrayList<>(pts)) {
				if (!(w.getPoint() instanceof net.osmand.plus.helpers.AmenityLocationPoint) || wh.isPointPassed(w)) {
					continue;
				}
				net.osmand.data.Amenity am = ((net.osmand.plus.helpers.AmenityLocationPoint) w.getPoint()).getAmenity();
				String sub = am.getSubType();
				for (int i = 0; i < POI_IDS.length; i++) {
					if (POI_IDS[i].equals(sub)) {
						int d = wh.getRouteDistance(w);
						if (d > 0 && d < poiNearestDist[i]) {
							poiNearestDist[i] = d;
						}
					}
				}
			}
		} catch (Throwable e) {
			Log.w(TAG, "nearest pois: " + e);
		}
		for (int i = 0; i < POI_IDS.length; i++) {
			if (poiNearestDist[i] != Integer.MAX_VALUE) {
				poiNearestKey[poiRows] = POI_IDS[i];
				poiNearestDist[poiRows] = poiNearestDist[i];
				poiRows++;
			}
		}
	}

	// Sygic-like box: next POI of each chosen category with its distance along the route
	private void drawPoiOverlay(Canvas c, float x, float top, float maxBottom) {
		ensurePoiPrefs();
		boolean any = false;
		for (String id : POI_IDS) {
			any |= isPoiSelected(id);
		}
		boolean visible = any && (poiAlwaysPref.get() || System.currentTimeMillis() < poiShowUntil);
		if (!visible) {
			poiBox.setEmpty();
			return;
		}
		updateNearestPois();
		float rowH = 44 * dp;
		int rows = Math.max(1, Math.min(poiRows, (int) ((maxBottom - top - 12 * dp) / rowH)));
		if (rows <= 0) {
			return;
		}
		float w = 128 * dp;
		poiBox.set(x, top, x + w, top + rows * rowH + 12 * dp);
		card(c, poiBox, 18 * dp);
		if (poiRows == 0) {
			text.setTextAlign(Paint.Align.CENTER);
			text.setTextSize(12 * dp);
			text.setColor(0xFFB8C0CA);
			c.drawText(italian() ? "Cerco POI…" : "Looking for POIs…", poiBox.centerX(), poiBox.top + 6 * dp + rowH / 2f + 4 * dp, text);
			return;
		}
		for (int i = 0; i < rows; i++) {
			float y = poiBox.top + 6 * dp + i * rowH;
			int icon = (int) (32 * dp);
			c.drawBitmap(NavMasterIcons.get(poiNearestKey[i], icon), x + 8 * dp, y + (rowH - icon) / 2f, bmp);
			net.osmand.plus.utils.FormattedValue fv = OsmAndFormatter.getFormattedDistanceValue(poiNearestDist[i], app);
			drawValue(c, fv.value, fv.unit, x + 8 * dp + icon + 10 * dp, y + rowH / 2f + 8 * dp, 21 * dp, Color.WHITE, Paint.Align.LEFT);
		}
	}
}
