import dayjs from "dayjs";
import relativeTime from "dayjs/plugin/relativeTime";
import { t } from "ttag";
dayjs.extend(relativeTime);

import type { RecentItem } from "metabase-types/api";

import type { SearchItem } from "../../types";

const dateBuckets = [
  {
    get title() {
      return t`Today`;
    },
    days: 1,
  },
  {
    get title() {
      return t`Yesterday`;
    },
    days: 2,
  },
  {
    get title() {
      return t`Last week`;
    },
    days: 7,
  },
  {
    get title() {
      return t`Earlier`;
    },
    days: Infinity,
  },
];

type RecentsGroup = {
  title: string;
  days: number;
  items: RecentItem[];
};

/**
 * groups recent items into date buckets
 */
export function getRecentGroups(items: RecentItem[]) {
  const now = dayjs();

  const groups = items.reduce(
    (groups: RecentsGroup[], item) => {
      const itemDate = dayjs(item.timestamp);

      for (const group of groups) {
        if (now.diff(itemDate, "days") < group.days) {
          group.items.push(item);
          break;
        }
      }
      return groups;
    },
    dateBuckets.map((bucket) => ({ ...bucket, items: [] })),
  );

  return groups.filter((group) => group.items.length > 0);
}

// put a recent item into the shape expected by ResultItem component
export const recentItemToResultItem = (item: RecentItem): SearchItem => ({
  ...item,
  ...("parent_collection" in item
    ? {
        collection: {
          ...item.parent_collection,
          id: item.parent_collection.id ?? "root",
        },
      }
    : {
        database_name: item.database.name,
      }),
});
