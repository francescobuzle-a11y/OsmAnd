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
patch(wah, 'ApplicationMode[] secondNextTurnSet = {CAR, BICYCLE, PEDESTRIAN, BOAT, SKI, TRUCK, MOTORCYCLE, HORSE, MOPED};',
      'ApplicationMode[] secondNextTurnSet = {BICYCLE, PEDESTRIAN, BOAT, SKI, MOTORCYCLE, HORSE, MOPED};')

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
# 12) Truck navigation defaults (Garmin-like: warning signs, heading-up, auto zoom, vehicle icon)
patch(st, '\t\tROTATE_MAP.setModeDefaultValue(ApplicationMode.PEDESTRIAN, ROTATE_MAP_BEARING);\n',
      '\t\tROTATE_MAP.setModeDefaultValue(ApplicationMode.PEDESTRIAN, ROTATE_MAP_BEARING);\n'
      '\t\t// NavMaster truck defaults\n'
      '\t\tROTATE_MAP.setModeDefaultValue(ApplicationMode.TRUCK, ROTATE_MAP_BEARING);\n'
      '\t\tAUTO_ZOOM_MAP.setModeDefaultValue(ApplicationMode.TRUCK, true);\n'
      '\t\tSHOW_TRAFFIC_WARNINGS.setModeDefaultValue(ApplicationMode.TRUCK, true);\n'
      '\t\tSHOW_PEDESTRIAN.setModeDefaultValue(ApplicationMode.TRUCK, true);\n'
      '\t\tSHOW_TUNNELS.setModeDefaultValue(ApplicationMode.TRUCK, true);\n'
      '\t\t((CommonPreference<Boolean>) SHOW_CAMERAS).setModeDefaultValue(ApplicationMode.TRUCK, true);\n'
      '\t\tSHOW_SPEED_LIMIT_WARNINGS.setModeDefaultValue(ApplicationMode.TRUCK, true);\n'
      '\t\tNAVIGATION_ICON.setModeDefaultValue(ApplicationMode.TRUCK, LocationIcon.MOVEMENT_CAR.name());\n')
# 13) Optimized release build signed with the repo key (same key as before, so updates install over old builds)
patch(os.path.join(O, 'build.gradle'), 'signingConfig signingConfigs.publishing', 'signingConfig signingConfigs.development')
# 14) 3D perspective map by default (tilted camera like Waze/iGO; OpenGL engine)
patch(os.path.join(S, 'settings', 'backend', 'OsmandSettings.java'),
      '"last_known_map_elevation", 90)', '"last_known_map_elevation", 50)')
# 15) Essential drawer menu for drivers: Search, Directions, Maps, Map/Screen config, Settings. Everything else hidden.
dm = os.path.join(S, 'settings', 'backend', 'menuitems', 'DrawerMenuItemsSettings.java')
patch(dm, '\t\thiddenByDefault.add(DRAWER_VEHICLE_METRICS_ID);\n',
      '\t\thiddenByDefault.add(DRAWER_VEHICLE_METRICS_ID);\n'
      '\t\t// NavMaster: essential menu for truck/camper/bus/car drivers\n'
      '\t\thiddenByDefault.add(DRAWER_SALE_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_MAP_MARKERS_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_TRIP_RECORDING_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_TRAVEL_GUIDES_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_MEASURE_DISTANCE_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_WEATHER_FORECAST_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_PLUGINS_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_HELP_ID);\n'
      '\t\thiddenByDefault.add(DRAWER_BUILDS_ID);\n')
# 16) Only road-vehicle profiles: truck and car (camper/bus profiles come as truck-based custom profiles)
patch(st, '"available_application_modes", "truck,car,bicycle,pedestrian,"', '"available_application_modes", "truck,car,"')
# 17) Route preview: one-tap "Simula" chip (drives the route with simulated GPS), Start resets it
ri = os.path.join(S, 'routepreparationmenu', 'MapRouteInfoMenu.java')
patch(ri, '\t\tfor (LocalRoutingParameter parameter : mode.parameters) {\n\t\t\tif (parameter instanceof MuteSoundRoutingParameter) {',
      '\t\t// NavMaster: one-tap navigation simulation\n'
      '\t\tif (!mapActivity.getRoutingHelper().isPublicTransportMode()) {\n'
      '\t\t\tView nmSim = createToolbarOptionView(true, mapActivity.getString(R.string.simulate_navigation),\n'
      '\t\t\t\t\tR.drawable.ic_action_start_navigation, R.drawable.ic_action_start_navigation, v -> {\n'
      '\t\t\t\t\t\tmapActivity.getApp().getSettings().simulateNavigation = true;\n'
      '\t\t\t\t\t\tnmSimulationStarted = true;\n'
      '\t\t\t\t\t\tclickRouteGo();\n'
      '\t\t\t\t\t});\n'
      '\t\t\tif (nmSim != null) {\n'
      '\t\t\t\toptionsContainer.addView(nmSim, getContainerButtonLayoutParams(mapActivity, true));\n'
      '\t\t\t}\n'
      '\t\t}\n'
      '\t\tfor (LocalRoutingParameter parameter : mode.parameters) {\n\t\t\tif (parameter instanceof MuteSoundRoutingParameter) {')
patch(ri, '\t\tstartButton.setOnClickListener(v -> clickRouteGo());\n',
      '\t\tstartButton.setOnClickListener(v -> {\n'
      '\t\t\tif (nmSimulationStarted) {\n'
      '\t\t\t\tnmSimulationStarted = false;\n'
      '\t\t\t\tmapActivity.getApp().getSettings().simulateNavigation = false;\n'
      '\t\t\t}\n'
      '\t\t\tclickRouteGo();\n'
      '\t\t});\n')
patch(ri, '\tprivate void updateControlButtons(MapActivity mapActivity, View mainView) {\n',
      '\tprivate static boolean nmSimulationStarted;\n\n\tprivate void updateControlButtons(MapActivity mapActivity, View mainView) {\n')
# 18) Smaller APK: only European languages (resources.arsc) and compressed native libraries
bgp = os.path.join(O, 'build.gradle')
patch(bgp, '\tdefaultConfig {\n\t\tminSdkVersion osmand_minSdk\n',
      '\tdefaultConfig {\n\t\tminSdkVersion osmand_minSdk\n'
      '\t\tresConfigs "en", "it", "de", "fr", "es", "pt", "nl", "pl", "ro", "cs", "sk", "hu", "sl", "hr", "bs", "sr", "mk", "sq", "bg", "el", "da", "sv", "nb", "fi", "lt", "lv", "et", "uk", "ru", "tr", "ca"\n')
patch(bgp, '\tlintOptions {\n\t\tlintConfig file("lint.xml")\n',
      '\tpackagingOptions {\n\t\tjniLibs {\n\t\t\tuseLegacyPackaging = true\n\t\t}\n\t}\n\n'
      '\tlintOptions {\n\t\tlintConfig file("lint.xml")\n')
# 19) Ready-made Camper and Bus profiles (truck routing, typical dimensions), created once on first start
am = os.path.join(S, 'settings', 'backend', 'ApplicationMode.java')
patch(am, '\t\tinitCustomModes(app);\n\t\tinitModesParams(app);\n',
      '\t\tinitCustomModes(app);\n\t\tinitModesParams(app);\n'
      '\t\ttry {\n\t\t\tnmCreateDriverProfiles(app);\n\t\t} catch (Exception e) {\n\t\t\tandroid.util.Log.e("NavMaster", "driver profiles", e);\n\t\t}\n')
patch(am, '\tprivate static void initModesParams(@NonNull OsmandApplication app) {\n',
      '\t// NavMaster: Camper and Bus profiles derived from Truck (same routing, restrictions and alerts)\n'
      '\tprivate static void nmCreateDriverProfiles(@NonNull OsmandApplication app) {\n'
      '\t\tOsmandSettings settings = app.getSettings();\n'
      '\t\tnet.osmand.plus.settings.backend.preferences.CommonPreference<Boolean> done =\n'
      '\t\t\t\tsettings.registerBooleanPreference("nm_driver_profiles_v1", false).makeGlobal();\n'
      '\t\tif (done.get()) {\n\t\t\treturn;\n\t\t}\n'
      '\t\tnmCreateProfile(app, "nm_camper", "Camper", "ic_action_camper", ProfileIconColors.GREEN, "3.2", "3.5", "7.5", "2.3");\n'
      '\t\tnmCreateProfile(app, "nm_bus", "Bus", "ic_action_bus_dark", ProfileIconColors.DARK_YELLOW, "3.8", "18", "12", "2.55");\n'
      '\t\tdone.set(true);\n'
      '\t}\n\n'
      '\tprivate static void nmCreateProfile(@NonNull OsmandApplication app, String key, String name, String icon,\n'
      '\t\t\tProfileIconColors color, String height, String weight, String length, String width) {\n'
      '\t\tif (valueOfStringKey(key, null) != null) {\n\t\t\treturn;\n\t\t}\n'
      '\t\tApplicationModeBuilder builder = createCustomMode(TRUCK, key, app)\n'
      '\t\t\t\t.setUserProfileName(name)\n'
      '\t\t\t\t.setIconResName(icon)\n'
      '\t\t\t\t.setIconColor(color)\n'
      '\t\t\t\t.setRouteService(RouteService.OSMAND)\n'
      '\t\t\t\t.setRoutingProfile(app.getSettings().ROUTING_PROFILE.getModeValue(TRUCK));\n'
      '\t\tApplicationMode mode = saveProfile(builder, app);\n'
      '\t\tmode.setDerivedProfile(app.getSettings().DERIVED_PROFILE.getModeValue(TRUCK));\n'
      '\t\tOsmandSettings settings = app.getSettings();\n'
      '\t\tsettings.getCustomRoutingProperty("height", "0").setModeValue(mode, height);\n'
      '\t\tsettings.getCustomRoutingProperty("weight", "0").setModeValue(mode, weight);\n'
      '\t\tsettings.getCustomRoutingProperty("length", "0").setModeValue(mode, length);\n'
      '\t\tsettings.getCustomRoutingProperty("width", "0").setModeValue(mode, width);\n'
      '\t\tchangeProfileAvailability(mode, true, app);\n'
      '\t}\n\n'
      '\tprivate static void initModesParams(@NonNull OsmandApplication app) {\n')
# 20) Driver layer: restriction banner, satellite "Arrivo" panel, Segnala / POI buttons in navigation
shutil.copy(os.path.join(A, 'navmaster', 'NavMasterDriverLayer.java'), os.path.join(S, 'views', 'layers', 'NavMasterDriverLayer.java'))
patch(os.path.join(S, 'views', 'MapLayers.java'), 'mapView.addLayer(new net.osmand.plus.views.layers.JunctionViewLayer(app), 9.5f);',
      'mapView.addLayer(new net.osmand.plus.views.layers.JunctionViewLayer(app), 9.5f);\n\t\tmapView.addLayer(new net.osmand.plus.views.layers.NavMasterDriverLayer(app), 9.6f);')
# 21) Reports saved as favourites are announced along the route (truck and derived profiles)
patch(st, '\tpublic final OsmandPreference<Boolean> SHOW_NEARBY_POI = new BooleanPreference(this, "show_nearby_poi", false).makeProfile().cache();\n',
      '\tpublic final OsmandPreference<Boolean> SHOW_NEARBY_POI = new BooleanPreference(this, "show_nearby_poi", false).makeProfile().cache();\n\n'
      '\t{\n\t\t((CommonPreference<Boolean>) SHOW_NEARBY_FAVORITES).setModeDefaultValue(ApplicationMode.TRUCK, true);\n\t}\n')
# 22) Road profiles: NavMaster data bar (arrive in / distance / arrival) replaces the route info bar
wa = os.path.join(S, 'settings', 'backend', 'WidgetsAvailabilityHelper.java')
patch(wa, '\t\t\tregWidgetVisibility(ROUTE_INFO, exceptDefault);\n',
      '\t\t\tregWidgetVisibility(ROUTE_INFO, exceptDefault).removeIf(m -> m == CAR || m == TRUCK\n'
      '\t\t\t\t\t|| m.getParent() == CAR || m.getParent() == TRUCK);\n')
# 23) Smooth navigation simulation: 4 position updates per second, realistic speed per road type with
#     gentle acceleration and slowing down before bends; speed factor and "skip ahead" controllable at runtime
simd = os.path.join(S, 'simulation')
patch(os.path.join(simd, 'LocationSimulationUtils.java'),
      '\tprivate static SimulatedLocation middleLocation(', '\tstatic SimulatedLocation middleLocation(')
patch(os.path.join(simd, 'LocationSimulationUtils.java'),
      '\tprivate static float getMaxSpeedForRoadType(', '\tstatic float getMaxSpeedForRoadType(')
patch(os.path.join(simd, 'OsmAndLocationSimulation.java'),
      'public class OsmAndLocationSimulation {\n',
      'public class OsmAndLocationSimulation {\n\n'
      '\t// NavMaster: runtime controls for the route simulation (set from the map)\n'
      '\tpublic static volatile float nmSpeedFactor = 1f;\n'
      '\tpublic static volatile float nmSkipMeters = 0f;\n')
lst = os.path.join(simd, 'LocationSimulationThread.java')
patch(lst, '\t\t\tlong timeout = (long) (LOCATION_TIMEOUT * 1000);\n\t\t\tfloat intervalTime = LOCATION_TIMEOUT;\n',
      '\t\t\tlong timeout = (long) ((useLocationTime ? LOCATION_TIMEOUT : NM_STEP) * 1000);\n'
      '\t\t\tfloat intervalTime = useLocationTime ? LOCATION_TIMEOUT : NM_STEP;\n')
patch(lst, '''				} else {
					Pair<SimulatedLocation, Float> pair = LocationSimulationUtils.createSimulatedLocation(
							current, directions, mode, meters, intervalTime, coeff, speed, realistic);
					current = pair.first;
					meters = pair.second;
				}''', '''				} else {
					// NavMaster smooth simulation
					float skip = OsmAndLocationSimulation.nmSkipMeters;
					if (skip > 0) {
						OsmAndLocationSimulation.nmSkipMeters = 0;
						current = nmAdvance(current, directions, skip);
					}
					float factor = Math.max(0.25f, OsmAndLocationSimulation.nmSpeedFactor);
					float target = nmTargetSpeed(current, directions);
					float dv = target - nmSpeed;
					nmSpeed += Math.max(-3.5f * intervalTime, Math.min(1.8f * intervalTime, dv));
					if (nmSpeed < 2f) {
						nmSpeed = 2f;
					}
					float step = nmSpeed * intervalTime * factor;
					current = nmAdvance(current, directions, step);
					meters = step / factor / coeff;
				}''')
patch(lst, '\tprivate void addNoise(@NonNull Location location) {\n', '''	private static final float NM_STEP = 0.25f;
	private float nmSpeed = 8f;

	private static SimulatedLocation nmAdvance(SimulatedLocation cur, List<SimulatedLocation> directions, float meters) {
		while (meters > 0 && !directions.isEmpty()) {
			SimulatedLocation next = directions.get(0);
			float d = cur.distanceTo(next);
			if (d <= meters) {
				meters -= d;
				cur = new SimulatedLocation(directions.remove(0));
			} else {
				cur = LocationSimulationUtils.middleLocation(cur, next, meters);
				meters = 0;
			}
		}
		return cur;
	}

	// target speed in m/s: road type and speed limit, capped for heavy vehicles, slower before sharp bends
	private float nmTargetSpeed(SimulatedLocation cur, List<SimulatedLocation> directions) {
		if (directions.isEmpty()) {
			return 3f;
		}
		SimulatedLocation next = directions.get(0);
		float kmh = LocationSimulationUtils.getMaxSpeedForRoadType(next.getHighwayType());
		float limit = next.getSpeedLimit() * 3.6f;
		if (limit > 5 && limit < kmh + 30) {
			kmh = limit;
		}
		kmh = Math.min(kmh, 90f);
		float target = kmh / 3.6f;
		// look ~120 m ahead for bends
		float dist = cur.distanceTo(next);
		float bearing = cur.bearingTo(next);
		for (int i = 1; i < directions.size() && dist < 120; i++) {
			SimulatedLocation a = directions.get(i - 1);
			SimulatedLocation b = directions.get(i);
			float nb = a.bearingTo(b);
			float turn = Math.abs(((nb - bearing) + 540f) % 360f - 180f);
			if (turn > 35 && a.distanceTo(b) > 3) {
				float bendSpeed = turn > 100 ? 5.5f : (turn > 60 ? 8f : 12f);
				// allow braking over the remaining distance
				float allowed = (float) Math.sqrt(bendSpeed * bendSpeed + 2 * 1.5f * dist);
				target = Math.min(target, allowed);
			}
			bearing = nb;
			dist += a.distanceTo(b);
		}
		return target;
	}

	private void addNoise(@NonNull Location location) {
''')
# 24) Sygic-style navigation screen for road profiles: NavMaster bottom panel shows the next manoeuvre,
#     so the top turn banner, the street name bar and the bottom-left speedometer are hidden; 3D tilt at start
patch(wa, '\t\tregWidgetVisibility(NEXT_TURN, nextTurnSet);\n',
      '\t\tregWidgetVisibility(NEXT_TURN, nextTurnSet).removeIf(m -> m == CAR || m == TRUCK\n'
      '\t\t\t\t|| m.getParent() == CAR || m.getParent() == TRUCK);\n')
patch(wa, '\t\tregWidgetVisibility(STREET_NAME, CAR, TRUCK);\n',
      '\t\tregWidgetVisibility(STREET_NAME, CAR, TRUCK).clear();\n')
patch(st, '\t\tSHOW_SPEEDOMETER.setModeDefaultValue(ApplicationMode.CAR, true);\n\t\tSHOW_SPEEDOMETER.setModeDefaultValue(ApplicationMode.TRUCK, true);\n',
      '\t\tSHOW_SPEEDOMETER.setModeDefaultValue(ApplicationMode.CAR, false);\n\t\tSHOW_SPEEDOMETER.setModeDefaultValue(ApplicationMode.TRUCK, false);\n')
patch(os.path.join(S, 'views', 'MapActions.java'), '\t\tfloat elevationAngle = settings.getLastKnownMapElevation();\n',
      '\t\tfloat elevationAngle = Math.min(settings.getLastKnownMapElevation(), 45f); // NavMaster: always start in 3D\n')
print('NavMaster patches applied OK')
PATCH_EOF
python3 "$W/gen_assets.py" "$ROOT/resources/rendering_styles/fonts/10_NotoSans-Bold.ttf" "$W"
python3 "$W/patch.py" "$ROOT/android" "$ROOT/resources" "$W"
