#!/bin/bash
# Dumps facts about the real Minecraft 26.3 jar so mod code can use correct names.
OUT=${1:-/tmp/inspect}; mkdir -p "$OUT"
{
  echo "== java =="; java -version 2>&1
  echo "== candidate jars =="
  find ~/.gradle ~/.cache -name "*.jar" -size +5M 2>/dev/null | head -50
} > "$OUT/00-env.txt" 2>&1
MC=""
for j in $(find ~/.gradle -name "*.jar" -size +5M 2>/dev/null); do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft.class"; then MC="$j"; break; fi
done
echo "MC jar: $MC" >> "$OUT/00-env.txt"
[ -z "$MC" ] && exit 0
unzip -l "$MC" | grep -E "assets/minecraft/(post_effect|shaders)/" | awk '{print $4}' > "$OUT/10-shader-files.txt"
mkdir -p "$OUT/x" && cd "$OUT/x" && unzip -qo "$MC" "assets/minecraft/post_effect/*" "assets/minecraft/shaders/post/*" "assets/minecraft/shaders/core/screenquad*" "assets/minecraft/shaders/include/*" 2>/dev/null
cd "$OUT"
for f in $(find x -type f | sort); do echo "######## $f"; cat "$f"; echo; done > 20-shader-contents.txt
unzip -l "$MC" | awk '{print $4}' | grep -E "\.class$" | grep -iE "PostChain|PostPass|GameRenderer|GuiGraphics|/Gui\.class|RenderPipelines|GlobalSettingsUniform|ScreenEffect|LevelRenderer|FogRenderer|SkyRenderer|LightTexture|Camera\.class|CameraType" > 30-class-list.txt
CP="$MC:$(find ~/.gradle -name '*.jar' -size +100k 2>/dev/null | tr '\n' ':')"
for c in $(sed -e 's#/#.#g' -e 's#\.class$##' 30-class-list.txt | grep -vE '\$' | head -40); do
  echo "######## $c"; javap -p -cp "$CP" "$c" 2>&1
done > 40-javap.txt
# Fabric API HUD classes
FAPI=$(find ~/.gradle -name "fabric-rendering-v1*.jar" -o -name "fabric-api-*.jar" 2>/dev/null | head -5)
echo "$FAPI" > 50-fabric-jars.txt
for j in $FAPI; do unzip -l "$j" | awk '{print $4}' | grep -iE "hud|Overlay|WorldRender|PostEffect|Shader" ; done > 51-fabric-classes.txt
