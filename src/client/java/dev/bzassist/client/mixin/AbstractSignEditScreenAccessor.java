package dev.bzassist.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the four sign lines so the Bazaar's custom amount/price sign can be filled in. */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
	@Accessor("messages")
	String[] bzassist$messages();
}