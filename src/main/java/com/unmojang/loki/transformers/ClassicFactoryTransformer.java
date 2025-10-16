package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("com.mojang.minecraft.MinecraftApplet")
public class ClassicFactoryTransformer extends ReallyLegacyFactoryTransformer {}
