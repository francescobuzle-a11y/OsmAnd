package net.osmand.plus.views.layers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.router.ExitInfo;
import net.osmand.router.TurnType;
import net.osmand.util.Algorithms;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * NavMaster junction view: a schematic perspective view of the next motorway junction
 * (lanes, arrows on the lanes to take, green direction sign, distance bar).
 * Original NavMaster design, drawn from OSM data (turn:lanes, destination, destination:ref, junction:ref).
 */
public class JunctionViewLayer extends OsmandMapLayer {

	private static final int SHOW_DISTANCE_M = 1200;
	private static final String DEMO_FILE = "navmaster_junction_demo";

	private static final int ROUTE_MAGENTA = 0xFFC2189A;
	private static final int SIGN_GREEN = 0xFF0B7A3B;
	private static final int SIGN_BLUE = 0xFF1F5FB4;
	private static final int EXIT_YELLOW = 0xFFFFD23F;

	private OsmandApplication app;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private float dp;
	private long lastDemoCheck;
	private long lastLog;
	private boolean demo;

	public JunctionViewLayer(@NonNull Context ctx) {
		super(ctx);
	}

	@Override
	public void initLayer(@NonNull net.osmand.plus.views.OsmandMapTileView view) {
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

	private static class Junction {
		int distance;
		int[] lanes;
		int turn;
		String exitRef;
		List<String> destinations = new ArrayList<>();
		boolean motorway = true;
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		try {
			drawJunction(canvas, settings);
		} catch (Throwable e) {
			Log.e("NavMasterJV", "draw failed", e);
		}
	}

	private void drawJunction(Canvas canvas, DrawSettings settings) {
		if (app == null) {
			return;
		}
		boolean demoNow = demoMode();
		Junction j = demoNow ? demoJunction() : currentJunction();
		long now = System.currentTimeMillis();
		if (now - lastLog > 4000) {
			lastLog = now;
			Log.i("NavMasterJV", "onDraw canvas=" + canvas.getWidth() + "x" + canvas.getHeight()
					+ " demo=" + demoNow + " junction=" + (j != null) + " dir=" + app.getAppPath(null));
		}
		if (j == null) {
			return;
		}
		boolean night = settings != null && settings.isNightMode();
		int w = canvas.getWidth();
		int h = canvas.getHeight();
		boolean landscape = w > h;
		float margin = 10 * dp;
		float top = topOffset(h);
		RectF panel;
		if (landscape) {
			panel = new RectF(w * 0.50f, top, w - margin, Math.min(h * 0.80f, top + (w * 0.5f - margin) * 0.66f));
		} else {
			float ph = Math.min(w * 0.62f, h * 0.36f);
			panel = new RectF(margin, top, w - margin, top + ph);
		}
		if (now - lastLog < 50) {
			Log.i("NavMasterJV", "panel=" + panel);
		}
		drawPanel(canvas, panel, j, night);
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

	private boolean demoMode() {
		long now = System.currentTimeMillis();
		if (now - lastDemoCheck > 1500) {
			lastDemoCheck = now;
			File dir = app.getAppPath(null);
			demo = (dir != null && new File(dir, DEMO_FILE).exists()) || "1".equals(sysProp("debug.navmaster.jv"));
			net.osmand.plus.helpers.TargetPoint tp = app.getTargetPointsHelper().getPointToNavigate();
			if (tp != null && tp.getOnlyName() != null && tp.getOnlyName().contains("NAVMASTER_DEMO")) {
				demo = true;
			}
		}
		return demo;
	}

	private static String sysProp(String key) {
		try {
			Class<?> c = Class.forName("android.os.SystemProperties");
			return (String) c.getMethod("get", String.class).invoke(null, key);
		} catch (Throwable e) {
			return "";
		}
	}

	private Junction demoJunction() {
		Junction j = new Junction();
		j.distance = 450;
		j.turn = TurnType.KR;
		j.exitRef = "12";
		j.lanes = new int[] {TurnType.C << 1, TurnType.C << 1, (TurnType.C << 1) | 1, (TurnType.TSLR << 1) | 1};
		j.destinations.add("A14 Bologna");
		j.destinations.add("Rimini Sud");
		j.destinations.add("San Marino");
		return j;
	}

	private Junction currentJunction() {
		RoutingHelper rh = app.getRoutingHelper();
		if (rh == null || !rh.isFollowingMode() || !rh.isRouteCalculated() || rh.isDeviatedFromRoute()) {
			return null;
		}
		NextDirectionInfo next = rh.getNextRouteDirectionInfo(new NextDirectionInfo(), false);
		if (next == null || next.directionInfo == null || next.distanceTo <= 5 || next.distanceTo > SHOW_DISTANCE_M) {
			return null;
		}
		RouteDirectionInfo di = next.directionInfo;
		TurnType tt = di.getTurnType();
		if (tt == null || tt.isRoundAbout()) {
			return null;
		}
		int[] lanes = tt.getLanes();
		ExitInfo exit = di.getExitInfo();
		String dest = di.getDestinationRefAndName();
		boolean slip = tt.getValue() == TurnType.KL || tt.getValue() == TurnType.KR
				|| tt.getValue() == TurnType.TSLL || tt.getValue() == TurnType.TSLR || tt.getValue() == TurnType.OFFR;
		boolean hasExit = exit != null && !exit.isEmpty();
		boolean hasLanes = lanes != null && lanes.length >= 2;
		if (!(hasExit || (slip && (hasLanes || !Algorithms.isEmpty(dest))))) {
			return null;
		}
		Junction j = new Junction();
		j.distance = next.distanceTo;
		j.turn = tt.getValue();
		j.lanes = hasLanes ? lanes : null;
		if (hasExit && !Algorithms.isEmpty(exit.getRef())) {
			j.exitRef = exit.getRef();
		}
		if (!Algorithms.isEmpty(dest)) {
			for (String part : dest.split("[;,]")) {
				String s = part.trim();
				if (!s.isEmpty() && j.destinations.size() < 3) {
					j.destinations.add(s);
				}
			}
		}
		if (j.destinations.isEmpty()) {
			String street = di.getStreetName();
			String ref = di.getRef();
			String s = !Algorithms.isEmpty(ref) ? ref + (Algorithms.isEmpty(street) ? "" : " " + street) : street;
			if (!Algorithms.isEmpty(s)) {
				j.destinations.add(s);
			}
		}
		if (hasExit && !Algorithms.isEmpty(exit.getExitStreetName()) && j.destinations.size() < 3) {
			j.destinations.add(exit.getExitStreetName());
		}
		return j;
	}

	private static boolean rightSide(int turn) {
		return turn == TurnType.KR || turn == TurnType.TSLR || turn == TurnType.TR
				|| turn == TurnType.TSHR || turn == TurnType.OFFR;
	}

	private static boolean leftSide(int turn) {
		return turn == TurnType.KL || turn == TurnType.TSLL || turn == TurnType.TL || turn == TurnType.TSHL;
	}

	private void drawPanel(Canvas canvas, RectF p, Junction j, boolean night) {
		float r = 14 * dp;
		canvas.save();
		path.reset();
		path.addRoundRect(p, r, r, Path.Direction.CW);
		canvas.clipPath(path);

		float pw = p.width();
		float ph = p.height();
		float horizon = p.top + ph * 0.50f;

		// sky and ground
		fill.setShader(new LinearGradient(0, p.top, 0, horizon,
				night ? 0xFF0E1726 : 0xFF6FA8DC, night ? 0xFF2A3A52 : 0xFFE3EEF6, Shader.TileMode.CLAMP));
		canvas.drawRect(p.left, p.top, p.right, horizon, fill);
		fill.setShader(new LinearGradient(0, horizon, 0, p.bottom,
				night ? 0xFF22301F : 0xFF9DB08F, night ? 0xFF121A11 : 0xFF6F8465, Shader.TileMode.CLAMP));
		canvas.drawRect(p.left, horizon, p.right, p.bottom, fill);
		fill.setShader(null);

		// road geometry: a main carriageway and, for exits/forks, a branch towards the turn side
		int side = rightSide(j.turn) ? 1 : leftSide(j.turn) ? -1 : 0;
		int n = j.lanes != null ? j.lanes.length : (side != 0 ? 3 : 2);
		float bottomW = pw * 0.96f;
		float cx = p.centerX();
		float bl = cx - bottomW / 2f;
		float br = cx + bottomW / 2f;
		float laneW = bottomW / n;

		int asphalt = night ? 0xFF3B4047 : 0xFF5A6068;
		int marking = night ? 0xFFCFD3D8 : 0xFFFFFFFF;

		// main road vanishing point (shifted away from the branch)
		float vpMainX = cx - side * pw * 0.10f;
		float vpBranchX = cx + side * pw * 0.40f;
		float topMainHalf = pw * 0.06f;

		// branch lanes = lanes on the turn side that are marked with a turn towards that side
		int branchLanes = 0;
		if (side != 0) {
			if (j.lanes != null) {
				for (int i = 0; i < n; i++) {
					int idx = side > 0 ? n - 1 - i : i;
					int t = TurnType.getPrimaryTurn(j.lanes[idx]);
					if ((side > 0 && (t == TurnType.TSLR || t == TurnType.TR || t == TurnType.KR || t == TurnType.TSHR))
							|| (side < 0 && (t == TurnType.TSLL || t == TurnType.TL || t == TurnType.KL || t == TurnType.TSHL))) {
						branchLanes++;
					} else {
						break;
					}
				}
			}
			if (branchLanes == 0) {
				branchLanes = 1;
			}
		}

		// draw main carriageway
		fill.setColor(asphalt);
		path.reset();
		path.moveTo(bl, p.bottom);
		path.lineTo(br, p.bottom);
		path.lineTo(vpMainX + topMainHalf, horizon);
		path.lineTo(vpMainX - topMainHalf, horizon);
		path.close();
		canvas.drawPath(path, fill);

		// draw branch
		float splitX = side > 0 ? br - branchLanes * laneW : bl + branchLanes * laneW;
		if (side != 0) {
			path.reset();
			float outer = side > 0 ? br : bl;
			path.moveTo(splitX, p.bottom);
			path.lineTo(outer, p.bottom);
			path.quadTo(outer + side * pw * 0.02f, horizon + ph * 0.18f, vpBranchX + side * topMainHalf * 0.6f, horizon);
			path.lineTo(vpBranchX - side * topMainHalf * 0.6f, horizon);
			path.quadTo(splitX + side * pw * 0.06f, horizon + ph * 0.22f, splitX, p.bottom);
			path.close();
			canvas.drawPath(path, fill);
		}

		// lane markings (dashed, perspective)
		stroke.setColor(marking);
		for (int i = 0; i <= n; i++) {
			float x0 = bl + i * laneW;
			boolean edge = i == 0 || i == n;
			boolean branchBoundary = side != 0 && Math.abs(x0 - splitX) < 1;
			float vx;
			float half = topMainHalf;
			if (side != 0 && ((side > 0 && x0 > splitX + 1) || (side < 0 && x0 < splitX - 1))) {
				vx = vpBranchX;
				half = topMainHalf * 0.6f;
			} else {
				vx = vpMainX;
			}
			float x1 = vx + (x0 - cx) / (bottomW / 2f) * half;
			stroke.setStrokeWidth((edge || branchBoundary ? 3.5f : 2.5f) * dp);
			if (edge || branchBoundary) {
				canvas.drawLine(x0, p.bottom, x1, horizon, stroke);
			} else {
				drawDashed(canvas, x0, p.bottom, x1, horizon);
			}
		}

		// arrows on the lanes to take
		if (j.lanes != null) {
			for (int i = 0; i < n; i++) {
				boolean active = (j.lanes[i] & 1) == 1;
				float laneCx = bl + (i + 0.5f) * laneW;
				boolean inBranch = side != 0 && ((side > 0 && laneCx > splitX) || (side < 0 && laneCx < splitX));
				float tx = inBranch ? vpBranchX : vpMainX;
				drawLaneArrow(canvas, laneCx, p.bottom, tx, horizon, laneW, active ? ROUTE_MAGENTA : (night ? 0x66FFFFFF : 0x88FFFFFF), active);
			}
		} else {
			float laneCx = side > 0 ? (splitX + br) / 2f : side < 0 ? (bl + splitX) / 2f : cx;
			drawLaneArrow(canvas, laneCx, p.bottom, side != 0 ? vpBranchX : vpMainX, horizon, laneW, ROUTE_MAGENTA, true);
		}

		// direction sign
		drawSign(canvas, p, j, side, horizon, night);

		// distance bar
		drawDistance(canvas, p, j);

		canvas.restore();
		// border
		stroke.setColor(night ? 0xFF000000 : 0x55000000);
		stroke.setStrokeWidth(1.5f * dp);
		canvas.drawRoundRect(p, r, r, stroke);
	}

	private void drawDashed(Canvas canvas, float x0, float y0, float x1, float y1) {
		// dashes shrink with distance
		float t = 0f;
		float seg = 0.16f;
		for (int k = 0; k < 40 && t < 0.95f; k++) {
			float t2 = Math.min(1f, t + seg * 0.55f);
			canvas.drawLine(lerp(x0, x1, ease(t)), lerp(y0, y1, ease(t)), lerp(x0, x1, ease(t2)), lerp(y0, y1, ease(t2)), stroke);
			t += seg;
			seg = Math.max(0.03f, seg * 0.85f);
		}
	}

	private static float ease(float t) {
		return t;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private void drawLaneArrow(Canvas canvas, float bx, float by, float tx, float ty, float laneW, int color, boolean big) {
		float x0 = bx;
		float y0 = by - laneW * 0.10f;
		float x1 = lerp(bx, tx, 0.55f);
		float y1 = lerp(by, ty, 0.55f);
		float w0 = laneW * (big ? 0.34f : 0.16f);
		float w1 = w0 * 0.45f;
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 1) {
			return;
		}
		float nx = -dy / len;
		float ny = dx / len;
		float headLen = len * 0.28f;
		float hx = x1 - dx / len * headLen;
		float hy = y1 - dy / len * headLen;
		path.reset();
		path.moveTo(x0 + nx * w0 / 2, y0 + ny * w0 / 2);
		path.lineTo(hx + nx * w1 / 2, hy + ny * w1 / 2);
		path.lineTo(hx + nx * w1 * 1.4f, hy + ny * w1 * 1.4f);
		path.lineTo(x1, y1);
		path.lineTo(hx - nx * w1 * 1.4f, hy - ny * w1 * 1.4f);
		path.lineTo(hx - nx * w1 / 2, hy - ny * w1 / 2);
		path.lineTo(x0 - nx * w0 / 2, y0 - ny * w0 / 2);
		path.close();
		fill.setColor(color);
		canvas.drawPath(path, fill);
		if (big) {
			stroke.setColor(0xFFFFFFFF);
			stroke.setStrokeWidth(1.5f * dp);
			canvas.drawPath(path, stroke);
		}
	}

	private void drawSign(Canvas canvas, RectF p, Junction j, int side, float horizon, boolean night) {
		if (j.destinations.isEmpty() && Algorithms.isEmpty(j.exitRef)) {
			return;
		}
		float pw = p.width();
		float signW = pw * 0.62f;
		float textSize = Math.max(12 * dp, Math.min(16 * dp, p.height() * 0.062f));
		text.setTextSize(textSize);
		int lines = Math.max(1, j.destinations.size());
		float lineH = textSize * 1.25f;
		float pad = 8 * dp;
		float signH = pad * 2 + lines * lineH + (Algorithms.isEmpty(j.exitRef) ? 0 : lineH * 0.9f);
		float cx = p.centerX() + side * pw * 0.14f;
		float left = Math.max(p.left + 8 * dp, Math.min(p.right - 8 * dp - signW, cx - signW / 2f));
		float top = p.top + 10 * dp;
		RectF sign = new RectF(left, top, left + signW, top + signH);

		// posts
		fill.setColor(night ? 0xFF6B7178 : 0xFF8A9199);
		float postW = 4 * dp;
		if (sign.bottom < horizon) {
			canvas.drawRect(sign.left + signW * 0.2f - postW / 2, sign.bottom, sign.left + signW * 0.2f + postW / 2, horizon, fill);
			canvas.drawRect(sign.right - signW * 0.2f - postW / 2, sign.bottom, sign.right - signW * 0.2f + postW / 2, horizon, fill);
		}

		fill.setColor(j.motorway ? SIGN_GREEN : SIGN_BLUE);
		canvas.drawRoundRect(sign, 6 * dp, 6 * dp, fill);
		stroke.setColor(0xFFFFFFFF);
		stroke.setStrokeWidth(2 * dp);
		RectF inner = new RectF(sign.left + 3 * dp, sign.top + 3 * dp, sign.right - 3 * dp, sign.bottom - 3 * dp);
		canvas.drawRoundRect(inner, 4 * dp, 4 * dp, stroke);

		float y = sign.top + pad;
		if (!Algorithms.isEmpty(j.exitRef)) {
			String label = app.getString(R.string.shared_string_exit) + " " + j.exitRef;
			text.setTextSize(textSize * 0.8f);
			float tw = text.measureText(label) + 10 * dp;
			RectF chip = new RectF(inner.right - tw - 4 * dp, y - 2 * dp, inner.right - 4 * dp, y + lineH * 0.8f);
			fill.setColor(EXIT_YELLOW);
			canvas.drawRoundRect(chip, 3 * dp, 3 * dp, fill);
			text.setColor(0xFF111111);
			canvas.drawText(label, chip.left + 5 * dp, chip.bottom - lineH * 0.2f, text);
			y += lineH * 0.9f;
			text.setTextSize(textSize);
		}
		// direction arrow on the sign
		float arrowX = inner.left + 12 * dp;
		float arrowY = y + lines * lineH / 2f;
		drawSignArrow(canvas, arrowX, arrowY, lineH * 0.9f, side);

		text.setColor(0xFFFFFFFF);
		float textLeft = inner.left + 28 * dp;
		float maxW = inner.right - 6 * dp - textLeft;
		for (String d : j.destinations) {
			String s = ellipsize(d, maxW);
			canvas.drawText(s, textLeft, y + lineH * 0.8f, text);
			y += lineH;
		}
	}

	private void drawSignArrow(Canvas canvas, float x, float y, float size, int side) {
		canvas.save();
		canvas.translate(x, y);
		canvas.rotate(side * 40f);
		path.reset();
		float s = size / 2f;
		path.moveTo(0, -s);
		path.lineTo(s * 0.7f, -s * 0.2f);
		path.lineTo(s * 0.22f, -s * 0.2f);
		path.lineTo(s * 0.22f, s);
		path.lineTo(-s * 0.22f, s);
		path.lineTo(-s * 0.22f, -s * 0.2f);
		path.lineTo(-s * 0.7f, -s * 0.2f);
		path.close();
		fill.setColor(0xFFFFFFFF);
		canvas.drawPath(path, fill);
		canvas.restore();
	}

	private String ellipsize(String s, float maxW) {
		if (text.measureText(s) <= maxW) {
			return s;
		}
		String e = s;
		while (e.length() > 1 && text.measureText(e + "…") > maxW) {
			e = e.substring(0, e.length() - 1);
		}
		return e + "…";
	}

	private void drawDistance(Canvas canvas, RectF p, Junction j) {
		float barH = Math.max(30 * dp, p.height() * 0.13f);
		RectF bar = new RectF(p.left, p.bottom - barH, p.right, p.bottom);
		fill.setColor(0xB3000000);
		canvas.drawRect(bar, fill);
		String dist = OsmAndFormatter.getFormattedDistance(j.distance, app);
		text.setColor(0xFFFFFFFF);
		text.setTextSize(barH * 0.52f);
		float tw = text.measureText(dist);
		canvas.drawText(dist, bar.left + 12 * dp, bar.centerY() + barH * 0.18f, text);
		float trackL = bar.left + 24 * dp + tw;
		float trackR = bar.right - 14 * dp;
		float trackH = 7 * dp;
		RectF track = new RectF(trackL, bar.centerY() - trackH / 2, trackR, bar.centerY() + trackH / 2);
		fill.setColor(0x55FFFFFF);
		canvas.drawRoundRect(track, trackH / 2, trackH / 2, fill);
		float progress = 1f - Math.min(1f, j.distance / (float) SHOW_DISTANCE_M);
		RectF done = new RectF(trackL, track.top, trackL + (trackR - trackL) * progress, track.bottom);
		fill.setColor(ROUTE_MAGENTA);
		canvas.drawRoundRect(done, trackH / 2, trackH / 2, fill);
	}
}
