import type {
  DragOverEvent,
  DragStartEvent,
  Modifier,
  SensorDescriptor,
} from "@dnd-kit/core";
import { DndContext, DragOverlay } from "@dnd-kit/core";
import { SortableContext, arrayMove } from "@dnd-kit/sortable";
import React, { useLayoutEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import _ from "underscore";

import GrabberS from "metabase/css/components/grabber.module.css";
import { getPortalRootElement } from "metabase/css/core/overlays/utils";
import { isNotNull } from "metabase/lib/types";

export type SortableDivider = {
  afterIndex: number;
  renderFn: () => React.ReactNode;
};

type ItemId = number | string;
export type DragEndEvent = {
  id: ItemId;
  newIndex: number;
  itemIds: ItemId[];
};

export type RenderItemProps<T> = {
  item: T;
  id: ItemId;
  isDragOverlay?: boolean;
  index: number;
};
type SortableListProps<T> = {
  items: T[];
  getId: (item: T) => ItemId;
  renderItem: ({
    item,
    id,
    isDragOverlay,
    index,
  }: RenderItemProps<T>) => JSX.Element | null;
  onSortStart?: (event: DragStartEvent) => void;
  onSortEnd?: ({ id, newIndex }: DragEndEvent) => void;
  sensors?: SensorDescriptor<any>[];
  modifiers?: Modifier[];
  useDragOverlay?: boolean;
  dividers?: SortableDivider[];
};

export const SortableList = <T,>({
  items,
  getId,
  renderItem,
  onSortStart,
  onSortEnd,
  sensors = [],
  modifiers = [],
  useDragOverlay = true,
  dividers,
}: SortableListProps<T>) => {
  const [itemIds, setItemIds] = useState<ItemId[]>([]);
  const [indexedItems, setIndexedItems] = useState<Partial<Record<ItemId, T>>>(
    {},
  );
  const [activeItem, setActiveItem] = useState<T | null>(null);

  const dividersByIndex = useMemo(() => {
    return (dividers ?? []).reduce((acc, item) => {
      acc.set(item.afterIndex, item);
      return acc;
    }, new Map<number, SortableDivider>());
  }, [dividers]);

  // layout effect to prevent layout shift on mount
  useLayoutEffect(() => {
    setItemIds(items.map(getId));
    setIndexedItems(_.indexBy(items, getId));
  }, [items, getId]);

  const sortableElements = useMemo(
    () =>
      itemIds
        .map((id, index) => {
          const item = indexedItems[id];
          const divider = dividersByIndex.get(index);
          if (item) {
            return (
              <React.Fragment key={id}>
                {divider ? divider.renderFn() : null}
                {renderItem({ item, id, index })}
              </React.Fragment>
            );
          }
        })
        .filter(isNotNull),
    [itemIds, indexedItems, dividersByIndex, renderItem],
  );

  const handleDragOver = ({ active, over }: DragOverEvent) => {
    if (over && active.id !== over.id) {
      setItemIds((ids) => {
        const oldIndex = ids.indexOf(active.id);
        const newIndex = ids.indexOf(over.id);
        return arrayMove(ids, oldIndex, newIndex);
      });
    }
  };

  const handleDragStart = (event: DragStartEvent) => {
    document.body.classList.add(GrabberS.grabbing);

    onSortStart?.(event);

    const item = items.find((item) => getId(item) === event.active.id);
    if (item) {
      setActiveItem(item);
    }
  };

  const handleDragEnd = () => {
    document.body.classList.remove(GrabberS.grabbing);
    if (activeItem && onSortEnd) {
      onSortEnd({
        id: getId(activeItem),
        newIndex: itemIds.findIndex((id) => id === getId(activeItem)),
        itemIds,
      });
      setActiveItem(null);
    }
  };

  return (
    <DndContext
      onDragOver={handleDragOver}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      sensors={sensors}
      modifiers={modifiers}
    >
      <SortableContext items={itemIds}>{sortableElements}</SortableContext>
      {useDragOverlay &&
        // to avoid offset of the dragged item if the list lives in a scrolled/portalled container
        // we need to render the DragOverlay in a separate portal
        // (https://docs.dndkit.com/api-documentation/draggable/drag-overlay#portals)
        createPortal(
          <DragOverlay
            // Used in e2e, because can't pass data-testid
            className="drag-overlay"
          >
            {activeItem
              ? renderItem({
                  item: activeItem,
                  id: getId(activeItem),
                  isDragOverlay: true,
                  index: items.indexOf(activeItem),
                })
              : null}
          </DragOverlay>,
          getPortalRootElement(),
        )}
    </DndContext>
  );
};
