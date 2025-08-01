import { useState } from "react";
import { t } from "ttag";

import {
  QuestionPickerModal,
  type QuestionPickerValueItem,
} from "metabase/common/components/Pickers/QuestionPicker";
import { replaceCard } from "metabase/dashboard/actions";
import { useDispatch } from "metabase/lib/redux";
import { Button, Flex } from "metabase/ui";
import type { Dashboard, VirtualDashboardCard } from "metabase-types/api";

import type { VisualizationProps } from "../types";

type Props = VisualizationProps & {
  dashcard: VirtualDashboardCard;
  dashboard: Dashboard;
  isEditingParameter?: boolean;
};

function DashCardPlaceholderInner({
  dashboard,
  dashcard,
  isDashboard,
  isEditing,
  isEditingParameter,
}: Props) {
  const [isQuestionPickerOpen, setQuestionPickerOpen] = useState(false);
  const dispatch = useDispatch();

  const handleSelectQuestion = (nextCard: QuestionPickerValueItem) => {
    dispatch(replaceCard({ dashcardId: dashcard.id, nextCardId: nextCard.id }));
    setQuestionPickerOpen(false);
  };

  if (!isDashboard) {
    return null;
  }

  const pointerEvents = isEditingParameter ? "none" : "all";

  return (
    <>
      <Flex
        p={2}
        style={{ flex: 1, pointerEvents }}
        opacity={isEditingParameter ? 0.25 : 1}
      >
        {isEditing && (
          <Flex
            direction="column"
            align="center"
            justify="center"
            gap="sm"
            w="100%"
          >
            <Button
              onClick={() => setQuestionPickerOpen(true)}
              onMouseDown={preventDragging}
              style={{ pointerEvents }}
            >{t`Select question`}</Button>
          </Flex>
        )}
      </Flex>
      {isQuestionPickerOpen && (
        <QuestionPickerModal
          title={t`Pick what you want to replace this with`}
          value={
            dashboard.collection_id
              ? {
                  id: dashboard.collection_id,
                  model: "collection",
                }
              : undefined
          }
          models={["card", "dataset", "metric"]}
          onChange={handleSelectQuestion}
          onClose={() => setQuestionPickerOpen(false)}
        />
      )}
    </>
  );
}

DashCardPlaceholderInner.displayName = "DashCardPlaceholder";

function preventDragging(e: React.MouseEvent<HTMLButtonElement>) {
  e.stopPropagation();
}

export const DashCardPlaceholder = Object.assign(DashCardPlaceholderInner, {
  getUiName: () => t`Empty card`,
  identifier: "placeholder",
  iconName: "table_spaced", // TODO replace

  canSavePng: false,
  noHeader: true,
  hidden: true,
  disableSettingsConfig: true,
  supportPreviewing: false,

  checkRenderable: () => {
    // always renderable
  },
});
