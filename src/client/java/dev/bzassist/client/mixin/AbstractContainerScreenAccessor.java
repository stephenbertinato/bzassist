package dev.bzassist.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the container's screen origin so slot highlights can be drawn in absolute GUI coordinates. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int bzassist$leftPos();

	@Accessor("topPos")
	int bzassist$topPos();
}
