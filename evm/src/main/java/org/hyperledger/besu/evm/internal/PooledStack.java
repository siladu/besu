/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm.internal;

import static com.google.common.base.Preconditions.checkArgument;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * An operand stack for the Ethereum Virtual Machine (EVM) designed for reuse from a pool.
 *
 * <p>Allocates the full maximum capacity upfront (unlike {@link FlexStack} which starts small and
 * grows). This makes it suitable for pooling: the allocation cost is paid once and the stack is
 * returned to the pool via {@link #reset()} at the end of each EVM frame, ready for the next
 * borrow.
 *
 * @param <T> the element type
 */
public class PooledStack<T> {

  private final T[] entries;
  private final int maxSize;
  private int top;

  /**
   * Instantiates a new Pooled stack, allocating the full capacity upfront.
   *
   * @param maxSize the maximum number of elements
   * @param klass the element class
   */
  @SuppressWarnings("unchecked")
  public PooledStack(final int maxSize, final Class<T> klass) {
    checkArgument(maxSize >= 0, "max size must be non-negative");
    this.entries = (T[]) Array.newInstance(klass, maxSize);
    this.maxSize = maxSize;
    this.top = -1;
  }

  /**
   * Get operand.
   *
   * @param offset the offset from the top of the stack (0 = top)
   * @return the operand
   * @throws UnderflowException if the offset is out of range
   */
  public T get(final int offset) {
    if (offset < 0 || offset >= size()) {
      throw new UnderflowException();
    }
    return entries[top - offset];
  }

  /**
   * Pop operand.
   *
   * @return the operand at the top of the stack
   * @throws UnderflowException if the stack is empty
   */
  public T pop() {
    if (top < 0) {
      throw new UnderflowException();
    }
    final T removed = entries[top];
    entries[top--] = null;
    return removed;
  }

  /**
   * Peek at the top operand without removing it.
   *
   * @return the top operand, or {@code null} if the stack is empty
   */
  public T peek() {
    if (top < 0) {
      return null;
    }
    return entries[top];
  }

  /**
   * Pops the specified number of operands from the stack.
   *
   * @param items the number of operands to pop
   * @throws IllegalArgumentException if items is not positive
   * @throws UnderflowException if items exceeds the current stack size
   */
  public void bulkPop(final int items) {
    checkArgument(items > 0, "number of items to pop must be greater than 0");
    if (items > size()) {
      throw new UnderflowException();
    }
    Arrays.fill(entries, top - items + 1, top + 1, null);
    top -= items;
  }

  /**
   * Trims the middle section of items out of the stack. Items below the cutpoint remain, and only
   * {@code itemsToKeep} items above the cutpoint are kept.
   *
   * @param cutPoint point at which to start removing items
   * @param itemsToKeep number of items on top to place at the cutPoint
   * @throws IllegalArgumentException if cutPoint or itemsToKeep is negative
   * @throws UnderflowException if there are fewer than itemsToKeep items above cutPoint
   */
  public void preserveTop(final int cutPoint, final int itemsToKeep) {
    checkArgument(cutPoint >= 0, "cutPoint must be positive");
    checkArgument(itemsToKeep >= 0, "itemsToKeep must be positive");
    if (itemsToKeep == 0) {
      if (cutPoint < size()) {
        bulkPop(top - cutPoint);
      }
    } else {
      int targetSize = cutPoint + itemsToKeep;
      int currentSize = size();
      if (targetSize > currentSize) {
        throw new UnderflowException();
      } else if (targetSize < currentSize) {
        System.arraycopy(entries, currentSize - itemsToKeep, entries, cutPoint, itemsToKeep);
        Arrays.fill(entries, targetSize, currentSize, null);
        top = targetSize - 1;
      }
    }
  }

  /**
   * Push operand.
   *
   * @param operand the operand to push
   * @throws OverflowException if the stack is full
   */
  public void push(final T operand) {
    final int nextTop = top + 1;
    if (nextTop == maxSize) {
      throw new OverflowException();
    }
    entries[nextTop] = operand;
    top = nextTop;
  }

  /**
   * Set operand at the given offset from the top.
   *
   * @param offset the offset from the top of the stack (0 = top)
   * @param operand the value to set
   * @throws UnderflowException if offset is negative
   * @throws OverflowException if offset exceeds the current stack size
   */
  public void set(final int offset, final T operand) {
    if (offset < 0) {
      throw new UnderflowException();
    } else if (offset >= size()) {
      throw new OverflowException();
    }
    entries[top - offset] = operand;
  }

  /**
   * Resets the stack for reuse. Nulls all live entries to allow garbage collection of held
   * references, then resets the top pointer. The backing array is retained for the next use.
   */
  public void reset() {
    if (top >= 0) {
      Arrays.fill(entries, 0, top + 1, null);
      top = -1;
    }
  }

  /**
   * Returns the current number of items in the stack.
   *
   * @return the stack size
   */
  public int size() {
    return top + 1;
  }

  /**
   * Is stack full.
   *
   * @return {@code true} if the stack has reached its maximum size
   */
  public boolean isFull() {
    return top + 1 >= maxSize;
  }

  /**
   * Is stack empty.
   *
   * @return {@code true} if the stack contains no items
   */
  public boolean isEmpty() {
    return top < 0;
  }

  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    for (int i = 0; i <= top; ++i) {
      builder.append(String.format("%n0x%04X ", i)).append(entries[i]);
    }
    return builder.toString();
  }
}
