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
			boolean navigating = rh.isFollowingMode() && rh.isRouteCalculated();
			if (!navigating) {
				return;
			}
			if (nmRoadMode(app.getSettings().getApplicationMode())) {
				drawDataBar(canvas, w, h, rh, night);
			}
			float top = topOffset(h);
			String demo = demoName();

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
						panel = new RectF(w * 0.50f, top, w - 10 * dp, Math.min(h - 150 * dp, top + (w * 0.5f) * 0.70f));
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
		return canvasHeight * 0.16f;
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
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(0x55000000);
		c.drawRoundRect(new RectF(r.left + 2 * dp, r.top + 3 * dp, r.right + 2 * dp, r.bottom + 3 * dp), 8 * dp, 8 * dp, fill);
		fill.setColor(BANNER_RED);
		c.drawRoundRect(r, 8 * dp, 8 * dp, fill);
		// warning triangle
		float s = r.height() * 0.62f;
		float cx = r.left + 10 * dp + s / 2f;
		float cy = r.centerY();
		path.reset();
		path.moveTo(cx, cy - s / 2f);
		path.lineTo(cx + s / 2f, cy + s / 2f);
		path.lineTo(cx - s / 2f, cy + s / 2f);
		path.close();
		fill.setColor(Color.WHITE);
		c.drawPath(path, fill);
		text.setColor(BANNER_RED);
		text.setTextAlign(Paint.Align.CENTER);
		text.setTextSize(s * 0.62f);
		c.drawText("!", cx, cy + s * 0.40f, text);
		text.setColor(Color.WHITE);
		text.setTextAlign(Paint.Align.LEFT);
		float ts = 16 * dp;
		text.setTextSize(ts);
		float avail = r.right - (cx + s / 2f + 10 * dp) - 8 * dp;
		while (text.measureText(restrictionText) > avail && ts > 10 * dp) {
			ts -= dp;
			text.setTextSize(ts);
		}
		c.drawText(restrictionText, cx + s / 2f + 10 * dp, cy + ts * 0.36f, text);
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
		float cellW = Math.min(112 * dp, (w - 96 * dp - left) / cells);
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
	}

	private void drawRoundButton(Canvas c, RectF r, int color, String symbol, String label) {
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(0x55000000);
		c.drawCircle(r.centerX() + 1.5f * dp, r.centerY() + 2.5f * dp, r.width() / 2f, fill);
		fill.setColor(Color.WHITE);
		c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, fill);
		fill.setColor(color);
		c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f - 3 * dp, fill);
		text.setTextAlign(Paint.Align.CENTER);
		text.setColor(Color.WHITE);
		text.setTextSize(22 * dp);
		c.drawText(symbol, r.centerX(), r.centerY() + 4 * dp, text);
		text.setTextSize(9.5f * dp);
		c.drawText(label, r.centerX(), r.centerY() + 17 * dp, text);
	}

	@Override
	public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		if (!buttonsVisible) {
			return false;
		}
		float pad = 6 * dp;
		if (inflate(reportBtn, pad).contains(point.x, point.y)) {
			showReportDialog();
			return true;
		}
		if (inflate(poiBtn, pad).contains(point.x, point.y)) {
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
		new AlertDialog.Builder(a)
				.setTitle(italian() ? "Segnala sulla strada" : "Report on the road")
				.setItems(items, (dlg, which) -> saveReport(a, me, items[which], REPORT_COLORS[which]))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
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

	private static final String[] POI_IDS = {"fuel", "services", "rest_area", "parking", "car_repair",
			"charging_station", "restaurant", "hotel", "toilets", "car_wash"};
	private static final String[] POI_IT = {"Carburante", "Aree di servizio", "Aree di sosta", "Parcheggi", "Officine",
			"Ricarica elettrica", "Ristoranti", "Hotel", "Servizi igienici", "Autolavaggi"};
	private static final String[] POI_EN = {"Fuel", "Service areas", "Rest areas", "Parking", "Repair shops",
			"Charging", "Restaurants", "Hotels", "Toilets", "Car wash"};

	private void showPoiDialog() {
		MapActivity a = getMapActivity();
		if (a == null) {
			return;
		}
		final String[] names = italian() ? POI_IT : POI_EN;
		final boolean[] checked = new boolean[POI_IDS.length];
		for (int i = 0; i < POI_IDS.length; i++) {
			checked[i] = app.getPoiFilters().isPoiFilterSelected(PoiUIFilter.STD_PREFIX + POI_IDS[i]);
		}
		new AlertDialog.Builder(a)
				.setTitle(italian() ? "POI da mostrare lungo il percorso" : "POIs to show along the route")
				.setMultiChoiceItems(names, checked, (dlg, which, isChecked) -> checked[which] = isChecked)
				.setPositiveButton(android.R.string.ok, (dlg, which) -> {
					for (int i = 0; i < POI_IDS.length; i++) {
						PoiUIFilter f = app.getPoiFilters().getFilterById(PoiUIFilter.STD_PREFIX + POI_IDS[i]);
						if (f == null) {
							continue;
						}
						boolean sel = app.getPoiFilters().isPoiFilterSelected(f);
						if (checked[i] && !sel) {
							app.getPoiFilters().addSelectedPoiFilter(f);
						} else if (!checked[i] && sel) {
							app.getPoiFilters().removeSelectedPoiFilter(f);
						}
					}
					if (view != null) {
						view.refreshMap();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}
}
