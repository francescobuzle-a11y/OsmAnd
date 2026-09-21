#!/bin/bash
# NavMaster Truck - build-time customization of upstream OsmAnd (runs on GitHub Actions)
# Usage from workspace root (contains android/ and resources/):  bash android/navmaster/apply.sh
set -euo pipefail
ROOT="$(pwd)"
W="$(mktemp -d)"
python3 -m pip install --quiet --break-system-packages pillow fonttools || python3 -m pip install --quiet pillow fonttools
cat > "$W/gen_assets.py" <<'GEN_EOF'
import os, base64
from PIL import Image, ImageDraw
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
import sys
FONT=sys.argv[1]
OUT=sys.argv[2]
GREEN_TOP=(47,168,79); GREEN_BOT=(22,110,48)

def text_path(txt, height):
    f=TTFont(FONT); gs=f.getGlyphSet(); cmap=f.getBestCmap(); upm=f['head'].unitsPerEm
    asc=f['hhea'].ascent; scale=height/ (asc*1.0)
    x=0; paths=[]
    for ch in txt:
        g=cmap[ord(ch)]; pen=SVGPathPen(gs)
        tp=TransformPen(pen,(scale,0,0,-scale,x*scale,asc*scale*0.95))
        gs[g].draw(tp); paths.append(pen.getCommands()); x+=gs[g].width
    return ' '.join(paths), x*scale

# --- wordmark vector (replaces OsmAnd text images, tinted by app) ---
d,w=text_path('NavMaster Truck',30)
W=int(w)+2
wm=f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{W}dp" android:height="36dp"
    android:viewportWidth="{W}" android:viewportHeight="36">
    <path android:fillColor="#BFBFBF" android:pathData="{d}"/>
</vector>
'''
open(f'{OUT}/image_text_navmaster.xml','w').write(wm)

# --- nav arrow geometry (108 viewport) ---
ARROW='M54,24 L80,82 L54,68 L28,82 Z'
fg=f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="0.62" android:scaleY="0.62" android:translateX="20.5" android:translateY="19">
        <path android:fillColor="#FFFFFF" android:pathData="{ARROW}"/>
        <path android:fillColor="#FFD23F" android:pathData="M22,92 L86,92 L86,98 L22,98 Z"/>
    </group>
</vector>
'''
bg='''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" android:endY="108">
                <item android:offset="0" android:color="#FF2FA84F"/>
                <item android:offset="1" android:color="#FF166E30"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''
mono=f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="0.62" android:scaleY="0.62" android:translateX="20.5" android:translateY="19">
        <path android:fillColor="#FFFFFF" android:pathData="{ARROW}"/>
    </group>
</vector>
'''
open(f'{OUT}/ic_launcher_navmaster_logo.xml','w').write(fg)
open(f'{OUT}/ic_launcher_navmaster_background.xml','w').write(bg)
open(f'{OUT}/ic_launcher_navmaster_monochrome.xml','w').write(mono)

# --- raster logo (splash + legacy mipmap) ---
def badge(size, rounded=True, pad=0.0):
    S=size*4
    im=Image.new('RGBA',(S,S),(0,0,0,0))
    grad=Image.new('RGBA',(S,S))
    gd=ImageDraw.Draw(grad)
    for y in range(S):
        t=y/(S-1); c=tuple(int(GREEN_TOP[i]*(1-t)+GREEN_BOT[i]*t) for i in range(3))+(255,)
        gd.line([(0,y),(S,y)],fill=c)
    mask=Image.new('L',(S,S),0); md=ImageDraw.Draw(mask)
    p=int(S*pad)
    if rounded: md.rounded_rectangle([p,p,S-p,S-p],radius=int(S*0.22),fill=255)
    else: md.ellipse([p,p,S-p,S-p],fill=255)
    im.paste(grad,(0,0),mask)
    d=ImageDraw.Draw(im)
    k=S/108.0
    def P(pts): return [(x*k,y*k) for x,y in pts]
    # scale arrow into the badge
    cx,cy=54,52; sc=0.78
    def T(pts): return [((x-54)*sc+cx,(y-54)*sc+cy) for x,y in pts]
    d.polygon(P(T([(54,24),(80,82),(54,68),(28,82)])),fill=(255,255,255,255))
    d.rectangle(P(T([(22,92),(86,98)])),fill=(255,210,63,255))
    return im.resize((size,size),Image.LANCZOS)

dens={'mdpi':1,'hdpi':1.5,'xhdpi':2,'xxhdpi':3,'xxxhdpi':4}
for dn,m in dens.items():
    os.makedirs(f'{OUT}/drawable-{dn}',exist_ok=True); os.makedirs(f'{OUT}/mipmap-{dn}',exist_ok=True)
    # splash: 240dp canvas like original, badge 60% centered
    S=int(240*m); canvas=Image.new('RGBA',(S,S),(0,0,0,0)); b=badge(int(S*0.62))
    canvas.paste(b,((S-b.size[0])//2,(S-b.size[1])//2),b)
    canvas.save(f'{OUT}/drawable-{dn}/ic_logo_splash_osmand.png',optimize=True)
    badge(int(48*m),pad=0.04).save(f'{OUT}/mipmap-{dn}/icon_nightly.png',optimize=True)
print('ok', W)
GEN_EOF
cat > "$W/navmaster.render.xml" <<'STYLE_EOF'
<renderingStyle name="navmaster" depends="default" defaultColor="#F4F1E8" version="1">
	<!-- NavMaster Truck: original high-contrast driving style (Garmin-like conventions:
	     warm light background, orange/yellow road hierarchy, magenta route line, dark night mode).
	     Everything not defined here falls back to OsmAnd default.render.xml -->

	<renderingAttribute name="defaultColor">
		<case noPolygons="true" attrColorValue="#00ebe7e4"/>
		<case attrColorValue="#F4F1E8">
			<apply_if nightMode="true" attrColorValue="#1A1E23"/>
		</case>
	</renderingAttribute>

	<renderingAttribute name="route">
		<case color="#E0C2189A" strokeWidth="14:9" color_0="#FF7A0E5E" strokeWidth_0="17:11" color_2="#FFFFFF" color_3="#FFFFFF" strokeWidth_3="5:7">
			<apply_if nightMode="true" color="#E0FF4FD8" color_0="#FF8A1470" color_2="#FFFFFF" color_3="#FFFFFF"/>
		</case>
	</renderingAttribute>

	<renderingAttribute name="motorwayRoadShadowColor">
		<case attrColorValue="#B06A12">
			<apply_if nightMode="true" attrColorValue="#00000000"/>
		</case>
	</renderingAttribute>
	<renderingAttribute name="trunkRoadShadowColor">
		<case attrColorValue="#B98524">
			<apply_if nightMode="true" attrColorValue="#00000000"/>
		</case>
	</renderingAttribute>
	<renderingAttribute name="primaryRoadShadowColor">
		<case attrColorValue="#C9A43C">
			<apply_if nightMode="true" attrColorValue="#00000000"/>
		</case>
	</renderingAttribute>
	<renderingAttribute name="secondaryRoadShadowColor">
		<case attrColorValue="#BFB27A">
			<apply_if nightMode="true" attrColorValue="#00000000"/>
		</case>
	</renderingAttribute>
	<renderingAttribute name="tertiaryRoadShadowColor">
		<case attrColorValue="#A7A7A7">
			<apply_if nightMode="true" attrColorValue="#00000000"/>
		</case>
	</renderingAttribute>
	<renderingAttribute name="residentialRoadShadowColor">
		<case attrColorValue="#40000000">
			<apply_if nightMode="true" attrColorValue="#00000000"/>
		</case>
	</renderingAttribute>
	<renderingAttribute name="motorwayRoadColor">
		<case attrColorValue="#F29A2E">
			<apply_if additional="tunnel=yes" attrColorValue="#F8C98F"/>
			<apply_if additional="covered=yes" attrColorValue="#F8C98F"/>
			<apply_if nightMode="true" attrColorValue="#C9761C">
				<apply_if additional="tunnel=yes" attrColorValue="#7A4A15"/>
				<apply_if additional="covered=yes" attrColorValue="#7A4A15"/>
			</apply_if>
		</case>
	</renderingAttribute>
	<renderingAttribute name="trunkRoadColor">
		<case attrColorValue="#F7B84A">
			<apply_if additional="tunnel=yes" attrColorValue="#FADBA3"/>
			<apply_if additional="covered=yes" attrColorValue="#FADBA3"/>
			<apply_if nightMode="true" attrColorValue="#B98A3A">
				<apply_if additional="tunnel=yes" attrColorValue="#6E5426"/>
				<apply_if additional="covered=yes" attrColorValue="#6E5426"/>
			</apply_if>
		</case>
	</renderingAttribute>
	<renderingAttribute name="primaryRoadColor">
		<case attrColorValue="#FFD966">
			<apply_if additional="tunnel=yes" attrColorValue="#FFEBB0"/>
			<apply_if additional="covered=yes" attrColorValue="#FFEBB0"/>
			<apply_if nightMode="true" attrColorValue="#A8904A">
				<apply_if additional="tunnel=yes" attrColorValue="#645530"/>
				<apply_if additional="covered=yes" attrColorValue="#645530"/>
			</apply_if>
		</case>
	</renderingAttribute>
	<renderingAttribute name="secondaryRoadColor">
		<case attrColorValue="#FFF1A8">
			<apply_if additional="tunnel=yes" attrColorValue="#FFF7D6"/>
			<apply_if additional="covered=yes" attrColorValue="#FFF7D6"/>
			<apply_if nightMode="true" attrColorValue="#8C8356">
				<apply_if additional="tunnel=yes" attrColorValue="#57523A"/>
				<apply_if additional="covered=yes" attrColorValue="#57523A"/>
			</apply_if>
		</case>
	</renderingAttribute>
	<renderingAttribute name="tertiaryRoadColor">
		<case attrColorValue="#FFFFFF">
			<apply_if additional="tunnel=yes" attrColorValue="#F2F2F2"/>
			<apply_if additional="covered=yes" attrColorValue="#F2F2F2"/>
			<apply_if nightMode="true" attrColorValue="#5E6670">
				<apply_if additional="tunnel=yes" attrColorValue="#454B52"/>
				<apply_if additional="covered=yes" attrColorValue="#454B52"/>
			</apply_if>
		</case>
	</renderingAttribute>
	<renderingAttribute name="residentialRoadColor">
		<case attrColorValue="#FFFFFF">
			<apply_if additional="tunnel=yes" attrColorValue="#F2F2F2"/>
			<apply_if additional="covered=yes" attrColorValue="#F2F2F2"/>
			<apply_if nightMode="true" attrColorValue="#4F565E">
				<apply_if additional="tunnel=yes" attrColorValue="#3C4248"/>
				<apply_if additional="covered=yes" attrColorValue="#3C4248"/>
			</apply_if>
		</case>
	</renderingAttribute>
	<renderingAttribute name="motorwayRoadLowZoomColor">
		<case attrColorValue="$motorwayRoadColor"/>
	</renderingAttribute>
	<renderingAttribute name="trunkRoadLowZoomColor">
		<case attrColorValue="$trunkRoadColor"/>
	</renderingAttribute>
	<renderingAttribute name="primaryRoadLowZoomColor">
		<case attrColorValue="$primaryRoadColor"/>
	</renderingAttribute>
	<renderingAttribute name="secondaryRoadLowZoomColor">
		<case attrColorValue="$secondaryRoadColor"/>
	</renderingAttribute>
	<renderingAttribute name="tertiaryRoadLowZoomColor">
		<case attrColorValue="$tertiaryRoadColor"/>
	</renderingAttribute>
	<renderingAttribute name="woodColor">
		<case nightMode="true" attrColorValue="#1E2E24"/>
		<case attrColorValue="#C9E2B0"/>
	</renderingAttribute>
	<renderingAttribute name="grassColor">
		<case nightMode="true" attrColorValue="#22332A"/>
		<case attrColorValue="#D8EBC4"/>
	</renderingAttribute>
	<renderingAttribute name="parkColor">
		<case nightMode="true" attrColorValue="#22332A"/>
		<case attrColorValue="#D3E9BE"/>
	</renderingAttribute>
	<renderingAttribute name="waterColor">
		<case nightMode="true" attrColorValue="#0E2A4A"/>
		<case attrColorValue="#9FC9EE"/>
	</renderingAttribute>
	<renderingAttribute name="buildingColor">
		<case nightMode="true" attrColorValue="#2A2F36"/>
		<case attrColorValue="#E1DCD2"/>
	</renderingAttribute>
	<renderingAttribute name="landuseResidentialColor">
		<case nightMode="true" attrColorValue="#20252B"/>
		<case attrColorValue="#ECE7DC"/>
	</renderingAttribute>
</renderingStyle>
STYLE_EOF
cat > "$W/icon_nightly.xml" <<'ICON_EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_navmaster_background" />
    <foreground android:drawable="@drawable/ic_launcher_navmaster_logo" />
    <monochrome android:drawable="@drawable/ic_launcher_navmaster_monochrome" />
</adaptive-icon>
ICON_EOF
cat > "$W/patch.py" <<'PATCH_EOF'
#!/usr/bin/env python3
# NavMaster Truck - source patches applied at build time on top of upstream OsmAnd
import re, sys, os, glob, shutil
A = sys.argv[1]            # android repo root
R = sys.argv[2]            # OsmAnd-resources root
P = sys.argv[3]            # unpacked package dir
O = os.path.join(A, 'OsmAnd')
S = os.path.join(O, 'src', 'net', 'osmand', 'plus')

def patch(path, old, new, count=1):
    s = open(path, encoding='utf-8').read()
    n = s.count(old)
    if n < count:
        sys.exit(f'PATCH FAILED: {path}\n  pattern not found: {old[:120]!r}')
    s = s.replace(old, new)
    open(path, 'w', encoding='utf-8').write(s)
    print(f'patched {os.path.relpath(path, A) if path.startswith(A) else path} ({n}x)')

# 1) App name
patch(os.path.join(O, 'build.gradle'), '"app_name", "OsmAnd Nightly"', '"app_name", "NavMaster Truck"')

# 2) Launcher icon, splash logo, wordmark
res = os.path.join(O, 'res')
for f in glob.glob(os.path.join(res, 'mipmap-*dpi', 'icon_nightly.png')):
    os.remove(f)
for f in glob.glob(os.path.join(res, 'drawable-*dpi', 'ic_logo_splash_osmand*.png')):
    os.remove(f)
for dn in ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']:
    shutil.copy(os.path.join(P, 'drawable-' + dn, 'ic_logo_splash_osmand.png'), os.path.join(res, 'drawable-' + dn, 'ic_logo_splash_osmand.png'))
    shutil.copy(os.path.join(P, 'drawable-' + dn, 'ic_logo_splash_osmand.png'), os.path.join(res, 'drawable-' + dn, 'ic_logo_splash_osmand_plus.png'))
    shutil.copy(os.path.join(P, 'mipmap-' + dn, 'icon_nightly.png'), os.path.join(res, 'mipmap-' + dn, 'icon_nightly.png'))
for n in ['ic_launcher_navmaster_background', 'ic_launcher_navmaster_logo', 'ic_launcher_navmaster_monochrome', 'image_text_navmaster']:
    shutil.copy(os.path.join(P, n + '.xml'), os.path.join(res, 'drawable', n + '.xml'))
shutil.copy(os.path.join(P, 'icon_nightly.xml'), os.path.join(res, 'mipmap-anydpi-v26', 'icon_nightly.xml'))
wm = open(os.path.join(res, 'drawable', 'image_text_navmaster.xml')).read()
for f in glob.glob(os.path.join(res, 'drawable', 'image_text_osmand*.xml')):
    open(f, 'w').write(wm)
print('assets copied')

# 3) Map style "NavMaster Truck" as default renderer
# sanity check: every $reference must be an attribute defined earlier in this file or a
# renderingConstant (OsmAnd cannot see the parent style's attributes while parsing a child)
_st = open(os.path.join(P, 'navmaster.render.xml'), encoding='utf-8').read()
_consts = set(re.findall(r'<renderingConstant name="([^"]+)"', open(os.path.join(R, 'rendering_styles', 'default.render.xml'), encoding='utf-8').read()))
_consts |= set(re.findall(r'<renderingConstant name="([^"]+)"', _st))
for _m in re.finditer(r'\$([A-Za-z_][A-Za-z0-9_]*)', _st):
    _name = _m.group(1)
    _defined = set(re.findall(r'<renderingAttribute name="([^"]+)"', _st[:_m.start()]))
    if _name not in _defined and _name not in _consts:
        sys.exit('STYLE ERROR: $' + _name + ' is not defined in navmaster.render.xml before use')
print('style references OK')
shutil.copy(os.path.join(P, 'navmaster.render.xml'), os.path.join(R, 'rendering_styles', 'navmaster.render.xml'))
rr = os.path.join(S, 'render', 'RendererRegistry.java')
patch(rr, 'public static final String DEFAULT_RENDER_FILE_PATH = "default.render.xml";',
      'public static final String DEFAULT_RENDER_FILE_PATH = "default.render.xml";\n\tpublic static final String NAVMASTER_RENDER = "NavMaster Truck";')
patch(rr, 'internalRenderers.put(DEFAULT_RENDER, DEFAULT_RENDER_FILE_PATH);',
      'internalRenderers.put(NAVMASTER_RENDER, "navmaster" + RENDERER_INDEX_EXT);\n\t\tinternalRenderers.put(DEFAULT_RENDER, DEFAULT_RENDER_FILE_PATH);')
patch(os.path.join(S, 'settings', 'backend', 'OsmandSettings.java'),
      'new StringPreference(this, "renderer", RendererRegistry.DEFAULT_RENDER)',
      'new StringPreference(this, "renderer", RendererRegistry.NAVMASTER_RENDER)')

# 4) NavMaster theme: green maneuver bar on top (default look of the top panel, white text)
res_kt = os.path.join(S, 'views', 'mapwidgets', 'appearance', 'PanelAppearanceResolver.kt')
patch(res_kt, """		var tintBackground = false
""", """		var tintBackground = false
		// NavMaster: the top (maneuver) panel is green by default
		if (panel == WidgetsPanel.TOP && backgroundMode == PanelBackgroundMode.DEFAULT) {
			backgroundColor = if (nightMode) 0xFF1C6B31.toInt() else 0xFF2E9E48.toInt()
			tintBackground = true
			primaryTextColor = Color.WHITE
			secondaryTextColor = 0xDDFFFFFF.toInt()
		}
""")

# 5) Default widgets for Truck/Car: street name bar, lanes, speed limit sign, current speed
wah = os.path.join(S, 'settings', 'backend', 'WidgetsAvailabilityHelper.java')
patch(wah, 'regWidgetVisibility(CURRENT_SPEED, BICYCLE, BOAT, SKI, PUBLIC_TRANSPORT, AIRCRAFT, HORSE, TRAIN);',
      'regWidgetVisibility(CURRENT_SPEED, CAR, TRUCK, MOTORCYCLE, BICYCLE, BOAT, SKI, PUBLIC_TRANSPORT, AIRCRAFT, HORSE, TRAIN);')
patch(wah, 'regWidgetVisibility(MAX_SPEED, none);', 'regWidgetVisibility(MAX_SPEED, CAR, TRUCK, MOTORCYCLE);')
patch(wah, 'regWidgetVisibility(STREET_NAME, CAR);', 'regWidgetVisibility(STREET_NAME, CAR, TRUCK);')
patch(wah, 'regWidgetVisibility(LANES, CAR, BICYCLE);', 'regWidgetVisibility(LANES, CAR, TRUCK, BICYCLE);')

# 6) iGO/Garmin-like search: open on Address (City > Street > Number) instead of History
patch(os.path.join(S, 'helpers', 'MapFragmentsHelper.java'),
      'REGULAR, showCategories ? CATEGORIES : HISTORY, searchLocation);',
      'REGULAR, showCategories ? CATEGORIES : ADDRESS, searchLocation);', count=2)
patch(os.path.join(res, 'values', 'strings.xml'),
      '<string name="start_search_from_city">First specify city/town/locality</string>',
      '<string name="start_search_from_city">City › Street › House number</string>')
patch(os.path.join(res, 'values-it', 'strings.xml'),
      '<string name="start_search_from_city">Prima specifica paese/città/località</string>',
      '<string name="start_search_from_city">Città › Via › Numero civico</string>')
# 7) Truck-first: Truck profile enabled and used by default
st = os.path.join(S, 'settings', 'backend', 'OsmandSettings.java')
patch(st, '"available_application_modes", "car,bicycle,pedestrian,public_transport,"',
      '"available_application_modes", "truck,car,bicycle,pedestrian,"')
patch(st, 'new CommonPreference<ApplicationMode>(this, "default_application_mode_string", ApplicationMode.DEFAULT)',
      'new CommonPreference<ApplicationMode>(this, "default_application_mode_string", ApplicationMode.TRUCK)')
# 8) Small in-app logo (welcome wizard etc.)
open(os.path.join(res, 'drawable', 'ic_action_osmand_logo.xml'), 'w').write("""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#1E8E3E" android:fillType="evenOdd"
        android:pathData="M5,2h14a3,3 0,0 1,3 3v14a3,3 0,0 1,-3 3h-14a3,3 0,0 1,-3 -3v-14a3,3 0,0 1,3 -3z M12,6.2L17.2,17.8L12,15L6.8,17.8Z"/>
</vector>
""")
print('in-app logo replaced')
# 9) Brand accent: OsmAnd orange -> NavMaster green
brand = {'osmand_orange': '#1E8E3E', 'osmand_orange_dark': '#166E30',
         'icon_color_osmand_light': '#1E8E3E', 'icon_color_osmand_dark': '#2FA84F',
         'status_bar_main_light': '#166E30'}
for cf in glob.glob(os.path.join(res, 'values*', 'colors.xml')):
    s = open(cf, encoding='utf-8').read(); n0 = s
    for name, val in brand.items():
        s = re.sub(r'(<color name="%s">)[^<]*(</color>)' % name, r'\g<1>%s\g<2>' % val, s)
    if s != n0:
        open(cf, 'w', encoding='utf-8').write(s); print('brand colors in', os.path.relpath(cf, A))
# 10) Junction view (schematic 3D view of motorway exits/forks) as a map layer
shutil.copy(os.path.join(A, 'navmaster', 'JunctionViewLayer.java'), os.path.join(S, 'views', 'layers', 'JunctionViewLayer.java'))
patch(os.path.join(S, 'views', 'MapLayers.java'), 'mapView.addLayer(mapInfoLayer, 9);',
      'mapView.addLayer(mapInfoLayer, 9);\n\t\tmapView.addLayer(new net.osmand.plus.views.layers.JunctionViewLayer(app), 9.5f);')

# 11) Squarer map buttons (rounded rectangles instead of circles)
patch(os.path.join(S, 'quickaction', 'MapButtonsHelper.java'),
      'registerIntPreference("default_map_button_corner_radius", ORIGINAL_VALUE)',
      'registerIntPreference("default_map_button_corner_radius", 10)')
print('NavMaster patches applied OK')
PATCH_EOF
python3 "$W/gen_assets.py" "$ROOT/resources/rendering_styles/fonts/10_NotoSans-Bold.ttf" "$W"
python3 "$W/patch.py" "$ROOT/android" "$ROOT/resources" "$W"
