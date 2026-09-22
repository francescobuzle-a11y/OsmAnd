package net.osmand.plus.views.layers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * NavMaster icon set (original drawings): POI categories as coloured tiles with a white pictogram,
 * road reports as warning triangles or round prohibition signs.
 */
public class NavMasterIcons {

	private static final Map<String, Bitmap> CACHE = new HashMap<>();

	public static final int POI_BLUE = 0xFF1565C0;

	public static synchronized Bitmap get(String key, int size) {
		String k = key + "@" + size;
		Bitmap b = CACHE.get(k);
		if (b == null) {
			b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
			draw(new Canvas(b), key, size);
			CACHE.put(k, b);
		}
		return b;
	}

	public static int poiColor(String key) {
		switch (key) {
			case "fuel":
			case "services":
				return 0xFF1565C0;
			case "parking":
			case "rest_area":
				return 0xFF2E7D32;
			case "car_repair":
			case "car_wash":
				return 0xFF455A64;
			case "charging_station":
				return 0xFF00897B;
			case "restaurant":
				return 0xFFEF6C00;
			case "hotel":
				return 0xFF6A1B9A;
			default:
				return 0xFF546E7A;
		}
	}

	private static void draw(Canvas c, String key, float s) {
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		if (key.startsWith("rep_")) {
			drawReport(c, key.substring(4), s, p);
			return;
		}
		// POI tile
		p.setColor(poiColor(key));
		c.drawRoundRect(new RectF(0, 0, s, s), s * 0.2f, s * 0.2f, p);
		p.setColor(Color.WHITE);
		switch (key) {
			case "fuel":
				pump(c, s * 0.5f, s * 0.52f, s * 0.62f, p);
				break;
			case "services":
				pump(c, s * 0.34f, s * 0.52f, s * 0.46f, p);
				forkKnife(c, s * 0.7f, s * 0.52f, s * 0.46f, p);
				break;
			case "parking":
				letter(c, "P", s * 0.5f, s * 0.5f, s * 0.72f, p);
				break;
			case "rest_area":
				picnic(c, s * 0.5f, s * 0.55f, s * 0.62f, p);
				break;
			case "car_repair":
				wrench(c, s * 0.5f, s * 0.5f, s * 0.64f, p);
				break;
			case "charging_station":
				bolt(c, s * 0.5f, s * 0.5f, s * 0.66f, p);
				break;
			case "restaurant":
				forkKnife(c, s * 0.5f, s * 0.52f, s * 0.64f, p);
				break;
			case "hotel":
				bed(c, s * 0.5f, s * 0.55f, s * 0.66f, p);
				break;
			case "toilets":
				letter(c, "WC", s * 0.5f, s * 0.5f, s * 0.44f, p);
				break;
			case "car_wash":
				car(c, s * 0.5f, s * 0.62f, s * 0.6f, p, Color.WHITE);
				drop(c, s * 0.34f, s * 0.26f, s * 0.16f, p);
				drop(c, s * 0.5f, s * 0.22f, s * 0.16f, p);
				drop(c, s * 0.66f, s * 0.26f, s * 0.16f, p);
				break;
			default:
				letter(c, "?", s * 0.5f, s * 0.5f, s * 0.6f, p);
		}
	}

	// ------------------------------------------------------------ reports

	private static void drawReport(Canvas c, String type, float s, Paint p) {
		boolean round = type.equals("no_trucks") || type.equals("limit") || type.equals("closed");
		if (round) {
			p.setStyle(Paint.Style.FILL);
			p.setColor(0xFFD32F2F);
			c.drawCircle(s / 2f, s / 2f, s * 0.48f, p);
			p.setColor(Color.WHITE);
			if (type.equals("closed")) {
				c.drawRoundRect(new RectF(s * 0.2f, s * 0.42f, s * 0.8f, s * 0.58f), s * 0.03f, s * 0.03f, p);
				return;
			}
			c.drawCircle(s / 2f, s / 2f, s * 0.36f, p);
			if (type.equals("no_trucks")) {
				truck(c, s * 0.5f, s * 0.52f, s * 0.5f, p, Color.BLACK);
			} else {
				// height / weight limit: arrows up and down with a bar
				p.setColor(Color.BLACK);
				Path t = new Path();
				t.moveTo(s * 0.5f, s * 0.2f);
				t.lineTo(s * 0.62f, s * 0.36f);
				t.lineTo(s * 0.38f, s * 0.36f);
				t.close();
				t.moveTo(s * 0.5f, s * 0.8f);
				t.lineTo(s * 0.62f, s * 0.64f);
				t.lineTo(s * 0.38f, s * 0.64f);
				t.close();
				c.drawPath(t, p);
				letter(c, "m", s * 0.5f, s * 0.5f, s * 0.24f, p);
			}
			return;
		}
		// warning triangle
		Path tri = new Path();
		tri.moveTo(s * 0.5f, s * 0.05f);
		tri.lineTo(s * 0.97f, s * 0.9f);
		tri.lineTo(s * 0.03f, s * 0.9f);
		tri.close();
		p.setStyle(Paint.Style.FILL);
		p.setColor(0xFFD32F2F);
		c.drawPath(tri, p);
		Path inner = new Path();
		inner.moveTo(s * 0.5f, s * 0.22f);
		inner.lineTo(s * 0.83f, s * 0.8f);
		inner.lineTo(s * 0.17f, s * 0.8f);
		inner.close();
		p.setColor(Color.WHITE);
		c.drawPath(inner, p);
		float cx = s * 0.5f;
		float cy = s * 0.6f;
		float g = s * 0.3f;
		switch (type) {
			case "accident":
				car(c, cx - g * 0.18f, cy + g * 0.12f, g * 0.9f, p, Color.BLACK);
				p.setColor(0xFFD32F2F);
				star(c, cx + g * 0.35f, cy - g * 0.28f, g * 0.28f, p);
				break;
			case "works":
				cone(c, cx, cy, g, p);
				break;
			case "obstacle":
				p.setColor(Color.BLACK);
				c.drawRoundRect(new RectF(cx - g * 0.4f, cy - g * 0.1f, cx + g * 0.4f, cy + g * 0.42f), g * 0.08f, g * 0.08f, p);
				c.drawCircle(cx + g * 0.1f, cy - g * 0.26f, g * 0.2f, p);
				break;
			case "stopped":
				car(c, cx, cy + g * 0.1f, g * 1.0f, p, Color.BLACK);
				break;
			case "traffic":
				car(c, cx - g * 0.42f, cy + g * 0.22f, g * 0.55f, p, Color.BLACK);
				car(c, cx + g * 0.02f, cy + g * 0.22f, g * 0.55f, p, Color.BLACK);
				car(c, cx - g * 0.2f, cy - g * 0.18f, g * 0.55f, p, Color.BLACK);
				break;
			case "camera":
				p.setColor(Color.BLACK);
				c.drawRoundRect(new RectF(cx - g * 0.5f, cy - g * 0.25f, cx + g * 0.35f, cy + g * 0.3f), g * 0.08f, g * 0.08f, p);
				Path lens = new Path();
				lens.moveTo(cx + g * 0.35f, cy - g * 0.1f);
				lens.lineTo(cx + g * 0.6f, cy - g * 0.25f);
				lens.lineTo(cx + g * 0.6f, cy + g * 0.3f);
				lens.lineTo(cx + g * 0.35f, cy + g * 0.15f);
				lens.close();
				c.drawPath(lens, p);
				p.setColor(Color.WHITE);
				c.drawCircle(cx - g * 0.08f, cy + g * 0.02f, g * 0.13f, p);
				break;
			case "police":
				p.setColor(0xFF1565C0);
				Path shield = new Path();
				shield.moveTo(cx, cy - g * 0.5f);
				shield.lineTo(cx + g * 0.45f, cy - g * 0.32f);
				shield.lineTo(cx + g * 0.36f, cy + g * 0.2f);
				shield.lineTo(cx, cy + g * 0.48f);
				shield.lineTo(cx - g * 0.36f, cy + g * 0.2f);
				shield.lineTo(cx - g * 0.45f, cy - g * 0.32f);
				shield.close();
				c.drawPath(shield, p);
				p.setColor(Color.WHITE);
				star(c, cx, cy - g * 0.02f, g * 0.2f, p);
				break;
			default:
				letter(c, "!", cx, cy + g * 0.05f, g * 1.2f, blackOf(p));
		}
	}

	private static Paint blackOf(Paint p) {
		p.setColor(Color.BLACK);
		return p;
	}

	// ------------------------------------------------------------ pictograms

	private static void letter(Canvas c, String t, float cx, float cy, float h, Paint p) {
		Paint tp = new Paint(p);
		tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
		tp.setTextAlign(Paint.Align.CENTER);
		tp.setTextSize(h);
		c.drawText(t, cx, cy + h * 0.36f, tp);
	}

	private static void pump(Canvas c, float cx, float cy, float h, Paint p) {
		float w = h * 0.5f;
		RectF body = new RectF(cx - w * 0.6f, cy - h * 0.5f, cx + w * 0.3f, cy + h * 0.5f);
		c.drawRoundRect(body, h * 0.06f, h * 0.06f, p);
		Paint hole = new Paint(p);
		hole.setColor(0x55000000);
		c.drawRect(body.left + w * 0.15f, body.top + h * 0.12f, body.right - w * 0.15f, body.top + h * 0.38f, hole);
		Paint st = new Paint(p);
		st.setStyle(Paint.Style.STROKE);
		st.setStrokeWidth(h * 0.08f);
		st.setStrokeCap(Paint.Cap.ROUND);
		Path hose = new Path();
		hose.moveTo(body.right, cy - h * 0.25f);
		hose.lineTo(cx + w * 0.62f, cy - h * 0.12f);
		hose.lineTo(cx + w * 0.62f, cy + h * 0.25f);
		hose.lineTo(cx + w * 0.42f, cy + h * 0.25f);
		c.drawPath(hose, st);
	}

	private static void forkKnife(Canvas c, float cx, float cy, float h, Paint p) {
		Paint st = new Paint(p);
		st.setStyle(Paint.Style.STROKE);
		st.setStrokeWidth(h * 0.09f);
		st.setStrokeCap(Paint.Cap.ROUND);
		float fx = cx - h * 0.18f;
		c.drawLine(fx, cy - h * 0.1f, fx, cy + h * 0.48f, st);
		st.setStrokeWidth(h * 0.06f);
		for (int i = -1; i <= 1; i++) {
			c.drawLine(fx + i * h * 0.1f, cy - h * 0.48f, fx + i * h * 0.1f, cy - h * 0.12f, st);
		}
		Path knife = new Path();
		float kx = cx + h * 0.2f;
		knife.moveTo(kx, cy - h * 0.48f);
		knife.quadTo(kx + h * 0.2f, cy - h * 0.2f, kx + h * 0.06f, cy);
		knife.lineTo(kx + h * 0.06f, cy + h * 0.48f);
		knife.lineTo(kx - h * 0.04f, cy + h * 0.48f);
		knife.lineTo(kx - h * 0.04f, cy - h * 0.48f);
		knife.close();
		c.drawPath(knife, p);
	}

	private static void picnic(Canvas c, float cx, float cy, float h, Paint p) {
		c.drawRect(cx - h * 0.5f, cy - h * 0.22f, cx + h * 0.5f, cy - h * 0.1f, p);
		c.drawRect(cx - h * 0.5f, cy + h * 0.1f, cx + h * 0.5f, cy + h * 0.2f, p);
		Paint st = new Paint(p);
		st.setStyle(Paint.Style.STROKE);
		st.setStrokeWidth(h * 0.08f);
		c.drawLine(cx - h * 0.3f, cy - h * 0.16f, cx - h * 0.42f, cy + h * 0.45f, st);
		c.drawLine(cx + h * 0.3f, cy - h * 0.16f, cx + h * 0.42f, cy + h * 0.45f, st);
		// tree
		c.drawCircle(cx, cy - h * 0.48f, h * 0.18f, p);
	}

	private static void wrench(Canvas c, float cx, float cy, float h, Paint p) {
		c.save();
		c.rotate(-45, cx, cy);
		c.drawRoundRect(new RectF(cx - h * 0.08f, cy - h * 0.25f, cx + h * 0.08f, cy + h * 0.5f), h * 0.06f, h * 0.06f, p);
		c.drawCircle(cx, cy - h * 0.3f, h * 0.2f, p);
		Paint cut = new Paint(p);
		cut.setColor(poiColor("car_repair"));
		c.drawRect(cx - h * 0.07f, cy - h * 0.55f, cx + h * 0.07f, cy - h * 0.3f, cut);
		c.restore();
	}

	private static void bolt(Canvas c, float cx, float cy, float h, Paint p) {
		Path b = new Path();
		b.moveTo(cx + h * 0.12f, cy - h * 0.5f);
		b.lineTo(cx - h * 0.3f, cy + h * 0.06f);
		b.lineTo(cx - h * 0.02f, cy + h * 0.06f);
		b.lineTo(cx - h * 0.12f, cy + h * 0.5f);
		b.lineTo(cx + h * 0.3f, cy - h * 0.08f);
		b.lineTo(cx + h * 0.02f, cy - h * 0.08f);
		b.close();
		c.drawPath(b, p);
	}

	private static void bed(Canvas c, float cx, float cy, float h, Paint p) {
		c.drawRect(cx - h * 0.5f, cy - h * 0.35f, cx - h * 0.4f, cy + h * 0.3f, p);
		c.drawRect(cx - h * 0.5f, cy + h * 0.02f, cx + h * 0.5f, cy + h * 0.16f, p);
		c.drawRect(cx + h * 0.4f, cy + h * 0.02f, cx + h * 0.5f, cy + h * 0.3f, p);
		c.drawCircle(cx - h * 0.22f, cy - h * 0.12f, h * 0.11f, p);
		c.drawRoundRect(new RectF(cx - h * 0.06f, cy - h * 0.2f, cx + h * 0.5f, cy + h * 0.02f), h * 0.06f, h * 0.06f, p);
	}

	private static void drop(Canvas c, float cx, float cy, float h, Paint p) {
		Path d = new Path();
		d.moveTo(cx, cy - h * 0.6f);
		d.quadTo(cx + h * 0.5f, cy + h * 0.05f, cx, cy + h * 0.4f);
		d.quadTo(cx - h * 0.5f, cy + h * 0.05f, cx, cy - h * 0.6f);
		c.drawPath(d, p);
	}

	static void car(Canvas c, float cx, float cy, float w, Paint p, int color) {
		p.setStyle(Paint.Style.FILL);
		p.setColor(color);
		float h = w * 0.42f;
		c.drawRoundRect(new RectF(cx - w * 0.5f, cy - h * 0.3f, cx + w * 0.5f, cy + h * 0.35f), h * 0.15f, h * 0.15f, p);
		Path cab = new Path();
		cab.moveTo(cx - w * 0.3f, cy - h * 0.3f);
		cab.lineTo(cx - w * 0.18f, cy - h * 0.8f);
		cab.lineTo(cx + w * 0.2f, cy - h * 0.8f);
		cab.lineTo(cx + w * 0.32f, cy - h * 0.3f);
		cab.close();
		c.drawPath(cab, p);
		c.drawCircle(cx - w * 0.28f, cy + h * 0.38f, h * 0.2f, p);
		c.drawCircle(cx + w * 0.28f, cy + h * 0.38f, h * 0.2f, p);
	}

	static void truck(Canvas c, float cx, float cy, float w, Paint p, int color) {
		p.setStyle(Paint.Style.FILL);
		p.setColor(color);
		float h = w * 0.5f;
		c.drawRect(cx - w * 0.5f, cy - h * 0.55f, cx + w * 0.18f, cy + h * 0.3f, p);
		c.drawRoundRect(new RectF(cx + w * 0.22f, cy - h * 0.25f, cx + w * 0.5f, cy + h * 0.3f), h * 0.08f, h * 0.08f, p);
		c.drawCircle(cx - w * 0.32f, cy + h * 0.38f, h * 0.16f, p);
		c.drawCircle(cx - w * 0.08f, cy + h * 0.38f, h * 0.16f, p);
		c.drawCircle(cx + w * 0.36f, cy + h * 0.38f, h * 0.16f, p);
	}

	private static void cone(Canvas c, float cx, float cy, float g, Paint p) {
		p.setColor(0xFFEF6C00);
		Path k = new Path();
		k.moveTo(cx, cy - g * 0.5f);
		k.lineTo(cx + g * 0.32f, cy + g * 0.35f);
		k.lineTo(cx - g * 0.32f, cy + g * 0.35f);
		k.close();
		c.drawPath(k, p);
		c.drawRect(cx - g * 0.48f, cy + g * 0.35f, cx + g * 0.48f, cy + g * 0.47f, p);
		p.setColor(Color.WHITE);
		c.drawRect(cx - g * 0.16f, cy - g * 0.05f, cx + g * 0.16f, cy + g * 0.07f, p);
	}

	private static void star(Canvas c, float cx, float cy, float r, Paint p) {
		Path st = new Path();
		for (int i = 0; i < 10; i++) {
			double a = Math.PI / 2 + i * Math.PI / 5;
			float rr = i % 2 == 0 ? r : r * 0.45f;
			float x = (float) (cx + Math.cos(a) * rr);
			float y = (float) (cy - Math.sin(a) * rr);
			if (i == 0) {
				st.moveTo(x, y);
			} else {
				st.lineTo(x, y);
			}
		}
		st.close();
		c.drawPath(st, p);
	}
}
