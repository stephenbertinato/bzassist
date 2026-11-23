package dev.bzassist.client;

import dev.bzassist.client.bazaar.GuideEngine;
import dev.bzassist.client.bazaar.ProfileState;
import dev.bzassist.client.mixin.AbstractSignEditScreenAccessor;
import dev.bzassist.client.mixin.ChatScreenAccessor;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

/**
 * Two things that remove typing from the bazaar flow.
 *
 * <ul>
 *     <li><strong>Chat.</strong> When the chat opens and the current step has a command, the box is
 *     pre-filled with it (for example {@code /bz Enchanted Pumpkin}), so the player presses Enter
 *     instead of typing the item name.</li>
 *     <li><strong>Sign.</strong> When the bazaar's custom amount or price sign opens, its first line is
 *     filled with the number the plan calls for, so the player only has to press Done.</li>
 * </ul>
 */
public final class InputAssist {
	private InputAssist() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof ChatScreen chat) {
				prefillChat(chat);
			} else if (screen instanceof AbstractSignEditScreen sign) {
				fillSign(sign);
			}
		});
	}

	private static void prefillChat(ChatScreen chat) {
		if (!ProfileState.onSkyblock()) {
			return;
		}

		String command = GuideEngine.step().command();

		if (command == null || command.isBlank()) {
			return;
		}

		EditBox input = ((ChatScreenAccessor) (Object) chat).bzassist$input();

		if (input == null) {
			return;
		}

		String current = input.getValue();

		if (current.isEmpty() || current.equals("/")) {
			input.setValue(command);
		}
	}

	private static void fillSign(AbstractSignEditScreen sign) {
		if (!ProfileState.onSkyblock()) {
			return;
		}

		String value = GuideEngine.signValue();

		if (value == null) {
			return;
		}

		String[] lines = ((AbstractSignEditScreenAccessor) (Object) sign).bzassist$messages();

		if (lines != null && lines.length > 0) {
			lines[0] = value;
		}
	}
}
