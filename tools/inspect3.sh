#!/bin/bash
OUT=${1:-/tmp/inspect}; mkdir -p "$OUT"
MC=$(grep '^MC jar:' "$OUT/00-env.txt" | sed 's/MC jar: //')
[ -z "$MC" ] && exit 0
CP="$MC:$(find ~/.gradle -name '*.jar' -size +100k 2>/dev/null | tr '\n' ':')"
{
for c in net.minecraft.client.KeyMapping 'net.minecraft.client.KeyMapping$Category' net.minecraft.resources.Identifier com.mojang.blaze3d.platform.InputConstants net.minecraft.network.chat.Component; do
  echo "######## $c"; javap -p -cp "$CP" "$c" 2>&1 | head -80
done
echo "######## GlobalSettingsUniform.update (bytecode)"
javap -p -c -constants -cp "$CP" net.minecraft.client.renderer.GlobalSettingsUniform 2>&1 | head -120
} > "$OUT/70-api.txt"
for j in $(find ~/.gradle -name "fabric-key-binding-api*.jar" -o -name "fabric-lifecycle-events*.jar" 2>/dev/null | head -4); do
  echo "######## $j"; unzip -l "$j" | awk '{print $4}' | grep -E "\.class$" | grep -vE "impl|mixin" ; done > "$OUT/71-fabric-api-classes.txt"
for c in net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper; do
  javap -p -cp "$CP" "$c" 2>&1; done >> "$OUT/71-fabric-api-classes.txt"
