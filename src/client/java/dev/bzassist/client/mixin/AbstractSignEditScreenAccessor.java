package dev.bzassist.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the four sign lines so the bazaar's "custom amount" sign can be filled in for the player.
 *
 * <p>The array is what the screen reads when it submits, so writing element 0 and clicking Done sends
 * our number. The visible field is built from the same array during {@code init}.</p>
 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
	@Accessor("messages")
	String[] bzassist$messages();
}
