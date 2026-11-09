#!/bin/bash
OUT=${1:-/tmp/inspect}; mkdir -p "$OUT"
MC=$(grep '^MC jar:' "$OUT/00-env.txt" | sed 's/MC jar: //')
CP="$MC:$(find ~/.gradle -name '*.jar' -size +100k 2>/dev/null | tr '\n' ':')"
{
echo "== fabric module jars with key/input in name"; find ~/.gradle -name "fabric-*key*.jar" -o -name "fabric-*input*.jar" 2>/dev/null
for j in $(find ~/.gradle -name "fabric-*key*.jar" 2>/dev/null | head -3); do
  unzip -l "$j" | awk '{print $4}' | grep -E "api/.*\.class$"
  for c in $(unzip -l "$j" | awk '{print $4}' | grep -E "api/.*\.class$" | grep -v '\$' | sed -e 's#/#.#g' -e 's#\.class$##'); do javap -p -cp "$CP" "$c" 2>&1 | head -30; done
done
echo "== InputConstants methods"; javap -p -cp "$CP" com.mojang.blaze3d.platform.InputConstants 2>&1 | grep -E "static" | grep -v "int KEY_" | head -40
echo "== Gui message methods"; javap -p -cp "$CP" net.minecraft.client.gui.Gui 2>&1 | grep -iE "message|overlay|chat" | head
echo "== Minecraft getInstance/getWindow/player"; javap -p -cp "$CP" net.minecraft.client.Minecraft 2>&1 | grep -E "getInstance|getWindow|LocalPlayer player|Gui gui|Options options" | head
echo "== LocalPlayer messages"; javap -p -cp "$CP" net.minecraft.client.player.LocalPlayer 2>&1 | grep -iE "message|overlay" | head
echo "== Player/Entity overlay"; javap -p -cp "$CP" net.minecraft.world.entity.player.Player 2>&1 | grep -iE "displayClientMessage|sendOverlay|Message" | head
} > "$OUT/80-keys.txt" 2>&1
