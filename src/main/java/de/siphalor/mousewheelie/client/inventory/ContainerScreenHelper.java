/*
 * Copyright 2020-2022 Siphalor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 */

package de.siphalor.mousewheelie.client.inventory;

import de.siphalor.mousewheelie.MWConfig;
import de.siphalor.mousewheelie.client.network.ClickEventFactory;
import de.siphalor.mousewheelie.client.network.InteractionManager;
import de.siphalor.mousewheelie.client.util.ItemStackUtils;
import de.siphalor.mousewheelie.client.util.accessors.ISlot;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
@SuppressWarnings("WeakerAccess")
public class ContainerScreenHelper<T extends HandledScreen<?>> {
	protected final T screen;
	protected final ClickEventFactory clickEventFactory;
	protected final IntSet lockedSlots = new IntRBTreeSet();
	protected final ReadWriteLock lockedSlotsLock = new ReentrantReadWriteLock();

	public static final int INVALID_SCOPE = Integer.MAX_VALUE;

	protected ContainerScreenHelper(T screen, ClickEventFactory clickEventFactory) {
		this.screen = screen;
		this.clickEventFactory = clickEventFactory;
	}

	@SuppressWarnings("unchecked")
	public static <T extends HandledScreen<?>> ContainerScreenHelper<T> of(T screen, ClickEventFactory clickEventFactory) {
		if (screen instanceof CreativeInventoryScreen) {
			return (ContainerScreenHelper<T>) new CreativeContainerScreenHelper<>((CreativeInventoryScreen) screen, clickEventFactory);
		}
		return new ContainerScreenHelper<>(screen, clickEventFactory);
	}

	public InteractionManager.InteractionEvent createClickEvent(Slot slot, int action, SlotActionType actionType) {
		if (isSlotLocked(slot)) {
			return null;
		}
		return clickEventFactory.create(slot, action, actionType);
	}

	public boolean isSlotLocked(Slot slot) {
		Lock readLock = lockedSlotsLock.readLock();
		readLock.lock();
		try {
			return lockedSlots.contains(slot.id);
		} finally {
			readLock.unlock();
		}
	}

	public void lockSlot(Slot slot) {
		Lock writeLock = lockedSlotsLock.writeLock();
		writeLock.lock();
		try {
			lockedSlots.add(slot.id);
		} finally {
			writeLock.unlock();
		}
	}

	public void unlockSlot(Slot slot) {
		Lock writeLock = lockedSlotsLock.writeLock();
		writeLock.lock();
		try {
			lockedSlots.remove(slot.id);
		} finally {
			writeLock.unlock();
		}
	}

	private InteractionManager.InteractionEvent unlockAfter(InteractionManager.InteractionEvent event, Slot slot) {
		if (event == null) {
			return null;
		}

		return new InteractionManager.CallbackEvent(() -> {
			InteractionManager.Waiter waiter = event.send();
			unlockSlot(slot);
			return waiter;
		}, event.shouldRunOnMainThread());
	}

	public void scroll(Slot referenceSlot, boolean scrollUp) {
		// Shall send determines whether items from the referenceSlot shall be moved to another scope. Otherwise the referenceSlot will receive items.
		boolean shallSend;
		if (MWConfig.scrolling.directionalScrolling) {
			shallSend = shallChangeInventory(referenceSlot, scrollUp);
		} else {
			shallSend = !scrollUp;
			scrollUp = false;
		}

		if (shallSend) {
			if (!referenceSlot.canInsert(ItemStack.EMPTY)) {
				sendStack(referenceSlot);
			}
			if (Screen.hasControlDown()) {
				sendAllOfAKind(referenceSlot);
			} else if (Screen.hasShiftDown()) {
				sendStack(referenceSlot);
			} else {
				sendSingleItem(referenceSlot);
			}
		} else {
			ItemStack referenceStack = referenceSlot.getStack().copy();
			int referenceScope = getScope(referenceSlot);
			if (Screen.hasShiftDown() || Screen.hasControlDown()) {
				for (Slot slot : screen.getScreenHandler().slots) {
					if (getScope(slot) == referenceScope) continue;
					if (ItemStackUtils.areItemsOfSameKind(slot.getStack(), referenceStack)) {
						sendStack(slot);
						if (!Screen.hasControlDown()) {
							break;
						}
					}
				}
			} else {
				Slot moveSlot = null;
				int stackSize = Integer.MAX_VALUE;
				for (Slot slot : screen.getScreenHandler().slots) {
					if (getScope(slot) == referenceScope) continue;
					if (getScope(slot) <= 0 == scrollUp) {
						if (ItemStackUtils.areItemsOfSameKind(slot.getStack(), referenceStack)) {
							if (slot.getStack().getCount() < stackSize) {
								stackSize = slot.getStack().getCount();
								moveSlot = slot;
								if (stackSize == 1) {
									break;
								}
							}
						}
					}
				}
				if (moveSlot != null) {
					sendSingleItem(moveSlot);
				}
			}
		}
	}

	public boolean shallChangeInventory(Slot slot, boolean scrollUp) {
		return (getScope(slot) <= 0) == scrollUp;
	}

	public boolean isHotbarSlot(Slot slot) {
		return ((ISlot) slot).mouseWheelie_getInvSlot() < 9;
	}

	public int getScope(Slot slot) {
		return getScope(slot, false);
	}

	public int getScope(Slot slot, boolean preferSmallerScopes) {
		if (slot.inventory == null || ((ISlot) slot).mouseWheelie_getInvSlot() >= slot.inventory.size() || !slot.canInsert(ItemStack.EMPTY)) {
			return INVALID_SCOPE;
		}
		if (screen instanceof AbstractInventoryScreen) {
			if (slot.inventory instanceof PlayerInventory) {
				if (isHotbarSlot(slot)) {
					return 0;
				} else if (((ISlot) slot).mouseWheelie_getInvSlot() >= 40) {
					return -1;
				} else {
					return 1;
				}
			} else {
				return 2;
			}
		} else {
			if (slot.inventory instanceof PlayerInventory) {
				if (isHotbarSlot(slot)) {
					if (MWConfig.general.hotbarScoping == MWConfig.General.HotbarScoping.HARD) {
						return -1;
					} else if (MWConfig.general.hotbarScoping == MWConfig.General.HotbarScoping.SOFT) {
						if (preferSmallerScopes) {
							return -1;
						}
					}
				}
				return 0;
			}
			return 1;
		}
	}

	public void runInScope(int scope, Consumer<Slot> slotConsumer) {
		runInScope(scope, false, slotConsumer);
	}

	public void runInScope(int scope, boolean preferSmallerScopes, Consumer<Slot> slotConsumer) {
		for (Slot slot : screen.getScreenHandler().slots) {
			if (getScope(slot, preferSmallerScopes) == scope) {
				slotConsumer.accept(slot);
			}
		}
	}

	public void sendSingleItem(Slot slot) {
		if (isSlotLocked(slot)) {
			return;
		}

		if (slot.getStack().getCount() == 1) {
			InteractionManager.push(clickEventFactory.create(slot, 0, SlotActionType.QUICK_MOVE));
			return;
		}
		InteractionManager.push(clickEventFactory.create(slot, 0, SlotActionType.PICKUP));
		InteractionManager.push(clickEventFactory.create(slot, 1, SlotActionType.PICKUP));
		InteractionManager.push(clickEventFactory.create(slot, 0, SlotActionType.QUICK_MOVE));
		InteractionManager.push(clickEventFactory.create(slot, 0, SlotActionType.PICKUP));
	}

	public void sendSingleItemLocked(Slot slot) {
		if (isSlotLocked(slot)) {
			return;
		}

		lockSlot(slot);
		if (slot.getStack().getCount() == 1) {
			InteractionManager.push(unlockAfter(clickEventFactory.create(slot, 0, SlotActionType.QUICK_MOVE), slot));
			return;
		}
		InteractionManager.push(clickEventFactory.create(slot, 0, SlotActionType.PICKUP));
		InteractionManager.push(clickEventFactory.create(slot, 1, SlotActionType.PICKUP));
		InteractionManager.push(clickEventFactory.create(slot, 0, SlotActionType.QUICK_MOVE));
		InteractionManager.push(unlockAfter(clickEventFactory.create(slot, 0, SlotActionType.PICKUP), slot));
	}

	public void sendStack(Slot slot) {
		InteractionManager.push(createClickEvent(slot, 0, SlotActionType.QUICK_MOVE));
	}

	public void sendStackLocked(Slot slot) {
		if (isSlotLocked(slot)) {
			return;
		}

		lockSlot(slot);
		InteractionManager.push(unlockAfter(clickEventFactory.create(slot, 0, SlotActionType.QUICK_MOVE), slot));
	}

	public void sendAllOfAKind(Slot referenceSlot) {
		ItemStack referenceStack = referenceSlot.getStack().copy();
		runInScope(getScope(referenceSlot), slot -> {
			if (ItemStackUtils.areItemsOfSameKind(slot.getStack(), referenceStack)) {
				sendStack(slot);
			}
		});
	}

	public void sendAllFrom(Slot referenceSlot) {
		runInScope(getScope(referenceSlot, true), true, this::sendStack);
	}

	public void dropStack(Slot slot) {
		if (isSlotLocked(slot)) {
			return;
		}

		InteractionManager.push(createClickEvent(slot, 1, SlotActionType.THROW));
	}

	public void dropStackLocked(Slot slot) {
		if (isSlotLocked(slot)) {
			return;
		}

		lockSlot(slot);
		InteractionManager.push(unlockAfter(clickEventFactory.create(slot, 1, SlotActionType.THROW), slot));
	}

	public void dropAllOfAKind(Slot referenceSlot) {
		ItemStack referenceStack = referenceSlot.getStack().copy();
		runInScope(getScope(referenceSlot), slot -> {
			if (ItemStackUtils.areItemsOfSameKind(slot.getStack(), referenceStack)) {
				dropStack(slot);
			}
		});
	}

	public void dropAllFrom(Slot referenceSlot) {
		runInScope(getScope(referenceSlot, true), true, this::dropStack);
	}
}
