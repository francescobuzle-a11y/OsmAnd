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
	private static final int LANES_DISTANCE_M = 800;
	private static final int LANE_GREEN = 0xFF2BD46A;
	private static final int LANE_GREEN_DARK = 0xFF0B7A3B;
	private static final int LANE_AMBER = 0xFFFFB300;
	private static final String DEMO_FILE = "navmaster_junction_demo";

	private static final int ROUTE_MAGENTA = 0xFFC2189A;
	private static final int SIGN_GREEN = 0xFF0B7A3B;
	private static final int SIGN_BLUE = 0xFF1F5FB4;
	private static final int EXIT_YELLOW = 0xFFFFD23F;

	// shared with NavMasterDriverLayer: when the junction panel was last drawn and where it ends
	public static volatile long nmPanelShownAt;
	public static volatile float nmPanelBottom;
	public static volatile RectF nmPanelRect;

	private OsmandApplication app;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final Path shaft = new Path();
	private boolean animPending;
	private float animPhase;
	private float dp;
	private long lastDemoCheck;
	private long lastLog;
	private boolean demo;
	private boolean demoLanes;

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
		boolean compact;
		int showFrom = 800;
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
		animPhase = (now % 1400L) / 1400f;
		RectF slot = NavMasterDriverLayer.nmSlot;
		RectF panel;
		if (slot != null && slot.width() > 150 * dp && slot.height() > 80 * dp) {
			// the driver layer reserves a slot: the right pane in landscape, under the top bar in portrait
			panel = new RectF(slot);
			if (j.compact) {
				panel.bottom = panel.top + Math.min(slot.height(), (landscape ? 104 : 110) * dp);
			}
		} else if (landscape) {
			panel = new RectF(w * 0.52f, top, w - margin, Math.min(h - 20 * dp, top + (w * 0.48f - margin) * 0.66f));
		} else {
			top += 64 * dp;
			panel = new RectF(margin, top, w - margin, top + Math.min(w * 0.56f, h * 0.30f));
		}
		if (!j.compact && !landscape && panel.height() > panel.width() * 0.72f) {
			panel.bottom = panel.top + panel.width() * 0.72f;
		}
		RectF fitted = NavMasterDriverLayer.nmFitPanel(panel, 150 * dp, 80 * dp, 8 * dp);
		if (fitted != null) {
			panel = fitted;
		}
		nmPanelShownAt = now;
		nmPanelBottom = panel.bottom;
		nmPanelRect = new RectF(panel);
		if (j.compact) {
			drawLaneStrip(canvas, panel, j, night);
		} else {
			drawPanel(canvas, panel, j, night);
		}
		scheduleAnim();
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
		// no top widgets (NavMaster bottom panel mode): just below the first row of map buttons
		return 30 * dp + (app.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 40 * dp : 0);
	}

	private boolean demoMode() {
		long now = System.currentTimeMillis();
		if (now - lastDemoCheck > 1500) {
			lastDemoCheck = now;
			File dir = app.getAppPath(null);
			demo = (dir != null && new File(dir, DEMO_FILE).exists()) || "1".equals(sysProp("debug.navmaster.jv"));
			net.osmand.plus.helpers.TargetPoint tp = app.getTargetPointsHelper().getPointToNavigate();
			String name = tp != null && tp.getOnlyName() != null ? tp.getOnlyName() : "";
			demoLanes = name.contains("NAVMASTER_LANES");
			if (name.contains("NAVMASTER_DEMO") || demoLanes) {
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
		if (demoLanes) {
			// compact guided lanes: keep left, straight (to take), straight (to take), turn right
			Junction lj = new Junction();
			lj.compact = true;
			lj.distance = 180;
			lj.turn = TurnType.C;
			lj.lanes = new int[] {TurnType.TL << 1, (TurnType.C << 1) | 1, (TurnType.C << 1) | 1, TurnType.TR << 1};
			return lj;
		}
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
			int showFrom = lanesShowDistance();
			if (hasLanes && next.distanceTo <= showFrom) {
				// no motorway sign, but the lanes are known: active lane guidance in the compact strip
				Junction lj = new Junction();
				lj.compact = true;
				lj.distance = next.distanceTo;
				lj.turn = tt.getValue();
				lj.lanes = lanes;
				lj.showFrom = showFrom;
				return lj;
			}
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

	// lanes appear earlier the faster you drive: 350 m in town, up to 1,2 km on the motorway
	private int lanesShowDistance() {
		net.osmand.Location loc = app.getLocationProvider().getLastKnownLocation();
		float sp = loc != null && loc.hasSpeed() ? loc.getSpeed() : 0;
		return (int) Math.max(350, Math.min(1200, sp * 22));
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

	private void scheduleAnim() {
		if (animPending || view == null || view.getView() == null) {
			return;
		}
		animPending = true;
		view.getView().postDelayed(() -> {
			animPending = false;
			if (view != null) {
				view.refreshMap();
			}
		}, 80);
	}

	// ---------------------------------------------------------- NavMaster active lane guidance
	// A perspective lane deck: the lanes to take carry an extruded 3D arrow with a light running
	// along it, the others stay flat and dim. A countdown bar shows how close the manoeuvre is.
	private void drawLaneStrip(Canvas canvas, RectF strip, Junction j, boolean night) {
		float r = 16 * dp;
		boolean urgent = j.distance <= 120;
		canvas.save();
		path.reset();
		path.addRoundRect(strip, r, r, Path.Direction.CW);
		canvas.clipPath(path);
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(0xFFFFFFFF);
		fill.setShader(new LinearGradient(0, strip.top, 0, strip.bottom, 0xF61E2632, 0xF60D1218, Shader.TileMode.CLAMP));
		canvas.drawRect(strip, fill);
		fill.setShader(null);

		float pad = 12 * dp;
		float barH = 5 * dp;
		net.osmand.plus.utils.FormattedValue fv = OsmAndFormatter.getFormattedDistanceValue(j.distance, app);
		float big = Math.min(26 * dp, strip.height() * 0.30f);
		text.setTextAlign(Paint.Align.LEFT);
		text.setTextSize(big);
		text.setColor(urgent ? LANE_AMBER : 0xFFFFFFFF);
		float dx = strip.left + pad;
		float dy = strip.centerY() + big * 0.16f;
		canvas.drawText(fv.value, dx, dy, text);
		float vw = text.measureText(fv.value);
		text.setTextSize(big * 0.46f);
		canvas.drawText(fv.unit, dx + vw + 3 * dp, dy, text);
		float unitW = text.measureText(fv.unit);
		text.setTextSize(9.5f * dp);
		text.setColor(0xFF8D98A6);
		text.setLetterSpacing(0.10f);
		boolean it = "it".equals(java.util.Locale.getDefault().getLanguage());
		canvas.drawText(it ? "CORSIE" : "LANES", dx, dy + big * 0.60f, text);
		text.setLetterSpacing(0f);
		float distW = Math.max(vw + unitW + 30 * dp, 76 * dp);

		RectF deck = new RectF(strip.left + distW, strip.top + 7 * dp,
				strip.right - 10 * dp, strip.bottom - 9 * dp - barH);
		if (deck.width() > 60 * dp && deck.height() > 40 * dp) {
			drawLaneDeck(canvas, deck, j, urgent);
		}

		float p = 1f - Math.min(1f, j.distance / (float) Math.max(200, j.showFrom));
		RectF track = new RectF(strip.left + pad, strip.bottom - barH - 6 * dp, strip.right - pad, strip.bottom - 6 * dp);
		fill.setColor(0x26FFFFFF);
		canvas.drawRoundRect(track, barH / 2f, barH / 2f, fill);
		fill.setColor(j.distance <= 60 ? 0xFFE53935 : urgent ? LANE_AMBER : LANE_GREEN);
		canvas.drawRoundRect(new RectF(track.left, track.top, track.left + Math.max(barH, track.width() * p), track.bottom),
				barH / 2f, barH / 2f, fill);

		canvas.restore();
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setPathEffect(null);
		stroke.setColor(0x66000000);
		stroke.setStrokeWidth(1.5f * dp);
		canvas.drawRoundRect(strip, r, r, stroke);
	}

	private void drawLaneDeck(Canvas canvas, RectF deck, Junction j, boolean urgent) {
		int n = j.lanes.length;
		if (n <= 0) {
			return;
		}
		float laneW = Math.min(deck.width() / n, 72 * dp);
		float allW = laneW * n;
		float cx = deck.centerX();
		float left = cx - allW / 2f;
		float baseY = deck.bottom;
		float topY = deck.top;
		float k = 0.82f; // perspective: the far edge is narrower

		// asphalt
		path.reset();
		path.moveTo(left, baseY);
		path.lineTo(left + allW, baseY);
		path.lineTo(cx + (allW / 2f) * k, topY);
		path.lineTo(cx - (allW / 2f) * k, topY);
		path.close();
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(0xFFFFFFFF);
		fill.setShader(new LinearGradient(0, topY, 0, baseY, 0xFF232A34, 0xFF39414D, Shader.TileMode.CLAMP));
		canvas.drawPath(path, fill);
		fill.setShader(null);

		// green corridor under the lanes to take
		for (int i = 0; i < n; i++) {
			if ((j.lanes[i] & 1) != 1) {
				continue;
			}
			float x0 = left + i * laneW;
			float x1 = x0 + laneW;
			path.reset();
			path.moveTo(x0, baseY);
			path.lineTo(x1, baseY);
			path.lineTo(cx + (x1 - cx) * k, topY);
			path.lineTo(cx + (x0 - cx) * k, topY);
			path.close();
			fill.setColor(0xFFFFFFFF);
			fill.setShader(new LinearGradient(0, topY, 0, baseY,
					urgent ? 0x14FFB300 : 0x142BD46A, urgent ? 0x4DFFB300 : 0x4D2BD46A, Shader.TileMode.CLAMP));
			canvas.drawPath(path, fill);
			fill.setShader(null);
		}

		// lane separators
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setPathEffect(null);
		stroke.setStrokeCap(Paint.Cap.BUTT);
		for (int i = 0; i <= n; i++) {
			float x0 = left + i * laneW;
			boolean edge = i == 0 || i == n;
			stroke.setColor(edge ? 0x59FFFFFF : 0x2EFFFFFF);
			stroke.setStrokeWidth((edge ? 2f : 1.2f) * dp);
			if (edge) {
				canvas.drawLine(x0, baseY, cx + (x0 - cx) * k, topY, stroke);
			} else {
				stroke.setPathEffect(new android.graphics.DashPathEffect(new float[]{7 * dp, 7 * dp}, 0));
				canvas.drawLine(x0, baseY, cx + (x0 - cx) * k, topY, stroke);
				stroke.setPathEffect(null);
			}
		}

		// arrows (clipped to the deck so nothing spills over the distance column)
		canvas.save();
		canvas.clipRect(deck.left - 6 * dp, deck.top - 2 * dp, deck.right + 6 * dp, deck.bottom + 4 * dp);
		for (int i = 0; i < n; i++) {
			int lane = j.lanes[i];
			boolean active = (lane & 1) == 1;
			float lcx = left + (i + 0.5f) * laneW;
			int[] turns = laneTurns(lane);
			for (int t = 0; t < turns.length; t++) {
				float offset = turns.length == 1 ? 0 : (t - (turns.length - 1) / 2f) * laneW * 0.26f;
				float scale = turns.length == 1 ? 1f : 0.72f;
				boolean main = t == 0;
				drawLaneArrow3d(canvas, turns[t], lcx + offset, baseY - 5 * dp, topY + 4 * dp, laneW * scale,
						active && main, active && !main, urgent);
			}
		}
		canvas.restore();
		stroke.setStrokeCap(Paint.Cap.BUTT);
	}

	private static int[] laneTurns(int lane) {
		int primary = TurnType.getPrimaryTurn(lane);
		int secondary = TurnType.getSecondaryTurn(lane);
		int tertiary = TurnType.getTertiaryTurn(lane);
		if (tertiary != 0 && secondary != 0) {
			return new int[] {primary, secondary, tertiary};
		}
		if (secondary != 0) {
			return new int[] {primary, secondary};
		}
		return new int[] {primary};
	}

	// tapered arrow with a dark extruded side (3D), a glow and a light running towards the turn
	private void drawLaneArrow3d(Canvas canvas, int turn, float lcx, float baseY, float topY, float laneW,
			boolean active, boolean activeAlt, boolean urgent) {
		double rad = Math.toRadians(turnAngleDeg(turn));
		float len = baseY - topY;
		float headLen = Math.min(laneW * 0.55f, len * 0.44f);
		float reach = len - headLen;
		// keep the whole glyph inside its own lane, like a Garmin lane icon: a hard turn
		// would otherwise sweep over the neighbouring lanes and over the distance text
		float sinA = Math.abs((float) Math.sin(rad));
		float cosA = Math.abs((float) Math.cos(rad));
		if (sinA > 0.5f) {
			float span = Math.min(len, laneW * 0.95f);
			headLen = Math.min(laneW * 0.55f, span * 0.44f);
			reach = span - headLen;
		}
		float halfHead = Math.max(laneW * (active ? 0.27f : activeAlt ? 0.18f : 0.13f) * 1.35f, laneW * 0.21f);
		float extent = sinA * (reach * 0.92f + headLen) + halfHead * cosA;
		float sideMax = laneW * 0.48f;
		if (extent > sideMax) {
			float f = Math.max(0.45f, sideMax / extent);
			reach *= f;
			headLen *= f;
		}
		float hx = lcx + (float) Math.sin(rad) * reach * 0.92f;
		float hy = baseY - (float) Math.cos(rad) * reach * 0.94f;
		if (turn == TurnType.TU || turn == TurnType.TRU) {
			hy = baseY - reach * 0.38f;
		}
		shaft.reset();
		shaft.moveTo(lcx, baseY);
		shaft.quadTo(lcx, baseY - reach * 0.58f, hx, hy);

		float speed = urgent ? 2f : 1f;
		float ph = (animPhase * speed) % 1f;
		float pulse = active ? 1f + 0.05f * (float) Math.sin(ph * 2 * Math.PI) : 1f;
		float sw = laneW * (active ? 0.27f : activeAlt ? 0.18f : 0.13f) * pulse;
		int top = active ? (urgent ? LANE_AMBER : LANE_GREEN)
				: activeAlt ? 0xCC2BD46A : 0x66FFFFFF;
		int side = active ? (urgent ? 0xFF8A5A00 : LANE_GREEN_DARK) : 0x33000000;

		stroke.setStyle(Paint.Style.STROKE);
		stroke.setStrokeCap(Paint.Cap.ROUND);
		stroke.setStrokeJoin(Paint.Join.ROUND);
		stroke.setPathEffect(null);
		if (active) {
			// soft glow
			stroke.setColor(urgent ? 0x33FFB300 : 0x332BD46A);
			stroke.setStrokeWidth(sw * 2.1f);
			canvas.drawPath(shaft, stroke);
		}
		canvas.save();
		canvas.translate(0, 3.5f * dp);
		stroke.setColor(side);
		stroke.setStrokeWidth(sw);
		canvas.drawPath(shaft, stroke);
		drawArrowHead(canvas, hx, hy, rad, laneW, side, headLen, sw);
		canvas.restore();
		stroke.setColor(top);
		stroke.setStrokeWidth(sw);
		canvas.drawPath(shaft, stroke);
		drawArrowHead(canvas, hx, hy, rad, laneW, top, headLen, sw);
		if (active) {
			stroke.setColor(0xCCFFFFFF);
			stroke.setStrokeWidth(sw * 0.26f);
			stroke.setPathEffect(new android.graphics.DashPathEffect(new float[]{5 * dp, 12 * dp}, -ph * 17 * dp));
			canvas.drawPath(shaft, stroke);
			stroke.setPathEffect(null);
		}
		stroke.setStrokeCap(Paint.Cap.BUTT);
	}

	private static float turnAngleDeg(int turn) {
		switch (turn) {
			case TurnType.KL: return -20;
			case TurnType.KR: return 20;
			case TurnType.TSLL: return -38;
			case TurnType.TSLR: return 38;
			case TurnType.TL: return -74;
			case TurnType.TR: return 74;
			case TurnType.TSHL: return -104;
			case TurnType.TSHR: return 104;
			case TurnType.TU: return -150;
			case TurnType.TRU: return 150;
			default: return 0;
		}
	}

	private void drawArrowHead(Canvas canvas, float x, float y, double rad, float laneW, int color, float headLen, float sw) {
		float dirx = (float) Math.sin(rad);
		float diry = -(float) Math.cos(rad);
		float hw = Math.max(sw * 1.35f, laneW * 0.21f);
		float tipx = x + dirx * headLen;
		float tipy = y + diry * headLen;
		float nx = -diry;
		float ny = dirx;
		path.reset();
		path.moveTo(tipx, tipy);
		path.lineTo(x + nx * hw, y + ny * hw);
		path.lineTo(x - nx * hw, y - ny * hw);
		path.close();
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(color);
		canvas.drawPath(path, fill);
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
			stroke.setStyle(Paint.Style.STROKE);
			stroke.setPathEffect(null);
			stroke.setColor(0xFFFFFFFF);
			stroke.setStrokeWidth(1.5f * dp);
			canvas.drawPath(path, stroke);
			// light pulse running towards the turn
			stroke.setColor(0xAAFFFFFF);
			stroke.setStrokeWidth(w1 * 0.7f);
			stroke.setStrokeCap(Paint.Cap.ROUND);
			stroke.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * dp, 12 * dp}, -animPhase * 18 * dp));
			canvas.drawLine(x0, y0, hx, hy, stroke);
			stroke.setPathEffect(null);
			stroke.setStrokeCap(Paint.Cap.BUTT);
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
			String exitWord = "it".equals(java.util.Locale.getDefault().getLanguage()) ? "Uscita" : app.getString(R.string.shared_string_exit);
			String label = exitWord + " " + j.exitRef;
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
