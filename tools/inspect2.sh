#!/bin/bash
OUT=${1:-/tmp/inspect}; mkdir -p "$OUT"
MC=$(grep '^MC jar:' "$OUT/00-env.txt" | sed 's/MC jar: //')
[ -z "$MC" ] && exit 0
W=/tmp/mcx; rm -rf $W; mkdir -p $W; cd $W; unzip -qo "$MC" 'net/*' 'com/mojang/*' 2>/dev/null
grep -rl "END_OF_FRAME_POST_EFFECT" . 2>/dev/null > "$OUT/60-end-of-frame-refs.txt"
CP="$MC:$(find ~/.gradle -name '*.jar' -size +100k 2>/dev/null | tr '\n' ':')"
for c in net.minecraft.client.renderer.GameRenderer net.minecraft.client.renderer.PostChain net.minecraft.client.renderer.PostPass net.minecraft.client.renderer.UniformValue 'net.minecraft.client.renderer.PostChainConfig$Pass' net.minecraft.client.renderer.ShaderManager; do
  echo "######## $c"; javap -p -c -constants -cp "$CP" "$c" 2>&1
done > "$OUT/61-javap-c.txt"
for f in $(cat "$OUT/60-end-of-frame-refs.txt"); do
  c=$(echo "$f" | sed -e 's#^\./##' -e 's#/#.#g' -e 's#\.class$##'); echo "######## $c"; javap -p -c -constants -cp "$CP" "$c" 2>&1 | grep -n -B8 -A8 "END_OF_FRAME" | head -120
done > "$OUT/62-end-of-frame-uses.txt"
javap -p -cp "$CP" 'net.minecraft.client.renderer.UniformValue' 'net.minecraft.client.renderer.UniformValue$Vec4Uniform' 2>&1 > "$OUT/63-uniformvalue.txt" || true
