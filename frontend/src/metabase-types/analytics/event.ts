import type {
  ChecklistItemCTA,
  ChecklistItemValue,
} from "metabase/home/components/Onboarding/types";
import type { KeyboardShortcutId } from "metabase/palette/shortcuts";
import type { Engine, VisualizationDisplay } from "metabase-types/api";

type SimpleEventSchema = {
  event: string;
  target_id?: number | null;
  triggered_from?: string | null;
  duration_ms?: number | null;
  result?: string | null;
  event_detail?: string | null;
};

type ValidateEvent<
  T extends SimpleEventSchema &
    Record<Exclude<keyof T, keyof SimpleEventSchema>, never>,
> = T;

export type CustomSMTPSetupClickedEvent = ValidateEvent<{
  event: "custom_smtp_setup_clicked";
  event_detail: "self-hosted" | "cloud";
}>;

export type CustomSMTPSetupSuccessEvent = ValidateEvent<{
  event: "custom_smtp_setup_success";
  event_detail: "self-hosted" | "cloud";
}>;

type CSVUploadClickedEvent = ValidateEvent<{
  event: "csv_upload_clicked";
  triggered_from: "add-data-modal" | "collection";
}>;

export type DatabaseAddClickedEvent = ValidateEvent<{
  event: "database_add_clicked";
  triggered_from: "db-list";
}>;

export type DatabaseEngineSelectedEvent = ValidateEvent<{
  event: "database_setup_selected";
  event_detail: Engine["driver-name"];
  triggered_from: "add-data-modal";
}>;

type OnboardingChecklistOpenedEvent = ValidateEvent<{
  event: "onboarding_checklist_opened";
}>;

type OnboardingChecklistItemExpandedEvent = ValidateEvent<{
  event: "onboarding_checklist_item_expanded";
  triggered_from: ChecklistItemValue;
}>;

type OnboardingChecklistItemCTAClickedEvent = ValidateEvent<{
  event: "onboarding_checklist_cta_clicked";
  triggered_from: ChecklistItemValue;
  event_detail: ChecklistItemCTA;
}>;

export type NewsletterToggleClickedEvent = ValidateEvent<{
  event: "newsletter-toggle-clicked";
  triggered_from: "setup";
  event_detail: "opted-in" | "opted-out";
}>;

export type NewIFrameCardCreatedEvent = ValidateEvent<{
  event: "new_iframe_card_created";
  event_detail: string | null;
  target_id: number | null;
}>;

export type MoveToTrashEvent = ValidateEvent<{
  event: "moved-to-trash";
  target_id: number | null;
  triggered_from: "collection" | "detail_page" | "cleanup_modal";
  duration_ms: number | null;
  result: "success" | "failure";
  event_detail:
    | "question"
    | "model"
    | "metric"
    | "dashboard"
    | "collection"
    | "dataset"
    | "indexed-entity"
    | "snippet";
}>;

export type ErrorDiagnosticModalOpenedEvent = ValidateEvent<{
  event: "error_diagnostic_modal_opened";
  triggered_from: "profile-menu" | "command-palette";
}>;

export type ErrorDiagnosticModalSubmittedEvent = ValidateEvent<{
  event: "error_diagnostic_modal_submitted";
  event_detail: "download-diagnostics" | "submit-report";
}>;

export type GsheetsConnectionClickedEvent = ValidateEvent<{
  event: "sheets_connection_clicked";
  triggered_from: "db-page" | "add-data-modal";
}>;

export type GsheetsImportClickedEvent = ValidateEvent<{
  event: "sheets_import_by_url_clicked";
  triggered_from: "sheets-url-popup";
}>;

export type KeyboardShortcutPerformEvent = ValidateEvent<{
  event: "keyboard_shortcut_performed";
  event_detail: KeyboardShortcutId;
}>;

export type NewEntityInitiatedEvent = ValidateEvent<{
  event: "plus_button_clicked";
  triggered_from: "model" | "metric" | "collection-header" | "collection-nav";
}>;

export type NewButtonClickedEvent = ValidateEvent<{
  event: "new_button_clicked";
  triggered_from: "app-bar" | "empty-collection";
}>;

export type NewButtonItemClickedEvent = ValidateEvent<{
  event: "new_button_item_clicked";
  triggered_from: "question" | "native-query" | "dashboard";
}>;

export type VisualizeAnotherWayClickedEvent = ValidateEvent<{
  event: "visualize_another_way_clicked";
  triggered_from: "question-list" | "dashcard-actions-panel";
}>;

export type VisualizerModalEvent = ValidateEvent<
  | {
      event:
        | "visualizer_add_more_data_clicked"
        | "visualizer_show_columns_clicked"
        | "visualizer_settings_clicked"
        | "visualizer_save_clicked"
        | "visualizer_close_clicked"
        | "visualizer_view_as_table_clicked";
      triggered_from: "visualizer-modal";
    }
  | {
      event: "visualizer_data_changed";
      event_detail:
        | "visualizer_viz_type_changed"
        | "visualizer_datasource_removed"
        | "visualizer_datasource_added"
        | "visualizer_datasource_replaced"
        | "visualizer_datasource_reset"
        | "visualizer_column_removed"
        | "visualizer_column_added";
      triggered_from: "visualizer-modal";
    }
>;

export type EmbeddingSetupStepKey =
  | "welcome"
  | "user-creation"
  | "data-connection"
  | "table-selection"
  | "processing"
  | "add-to-your-app"
  | "done";
export type EmbeddingSetupStepSeenEvent = ValidateEvent<{
  event: "embedding_setup_step_seen";
  event_detail: EmbeddingSetupStepKey;
}>;

export type EmbeddingSetupClickEvent = ValidateEvent<{
  event: "embedding_setup_click";
  event_detail:
    | "setup-up-manually"
    | "add-data-later-or-skip"
    | "snippet-copied"
    | "ill-do-this-later";
  triggered_from: EmbeddingSetupStepKey;
}>;

export type EventsClickedEvent = ValidateEvent<{
  event: "events_clicked";
  triggered_from: "chart" | "collection";
}>;

export type AddDataModalOpenedEvent = ValidateEvent<{
  event: "data_add_modal_opened";
  triggered_from: "getting-started" | "left-nav";
}>;

export type AddDataModalTabEvent = ValidateEvent<{
  event: "csv_tab_clicked" | "sheets_tab_clicked" | "database_tab_clicked";
  triggered_from: "add-data-modal";
}>;

export type DashboardFilterCreatedEvent = ValidateEvent<{
  event: "dashboard_filter_created";
  target_id: number | null;
  triggered_from: VisualizationDisplay | null;
  event_detail: string | null;
}>;

export type DashboardFilterMovedEvent = ValidateEvent<{
  event: "dashboard_filter_moved";
  target_id: number | null;
  triggered_from: VisualizationDisplay | null;
  event_detail: VisualizationDisplay | null;
}>;

export type SdkIframeEmbedSetupExperience =
  | "dashboard"
  | "chart"
  | "exploration";

export type EmbedWizardExperienceSelectedEvent = ValidateEvent<{
  event: "embed_wizard_experience_selected";
  event_detail: SdkIframeEmbedSetupExperience;
}>;

export type EmbedWizardResourceSelectedEvent = ValidateEvent<{
  event: "embed_wizard_resource_selected";
  event_detail: SdkIframeEmbedSetupExperience;
  target_id: number;
}>;

export type EmbedWizardOptionChangedEvent = ValidateEvent<{
  event: "embed_wizard_option_changed";
  event_detail: string;
}>;

export type EmbedWizardAuthSelectedEvent = ValidateEvent<{
  event: "embed_wizard_auth_selected";
  event_detail: "sso" | "user-session";
}>;

export type EmbedWizardCodeCopiedEvent = ValidateEvent<{
  event: "embed_wizard_code_copied";
}>;

export type EmbedWizardEvent =
  | EmbedWizardExperienceSelectedEvent
  | EmbedWizardResourceSelectedEvent
  | EmbedWizardOptionChangedEvent
  | EmbedWizardAuthSelectedEvent
  | EmbedWizardCodeCopiedEvent;

export type SimpleEvent =
  | CustomSMTPSetupClickedEvent
  | CustomSMTPSetupSuccessEvent
  | CSVUploadClickedEvent
  | DatabaseAddClickedEvent
  | DatabaseEngineSelectedEvent
  | NewIFrameCardCreatedEvent
  | NewsletterToggleClickedEvent
  | OnboardingChecklistOpenedEvent
  | OnboardingChecklistItemExpandedEvent
  | OnboardingChecklistItemCTAClickedEvent
  | MoveToTrashEvent
  | ErrorDiagnosticModalOpenedEvent
  | ErrorDiagnosticModalSubmittedEvent
  | GsheetsConnectionClickedEvent
  | GsheetsImportClickedEvent
  | KeyboardShortcutPerformEvent
  | NewEntityInitiatedEvent
  | NewButtonClickedEvent
  | NewButtonItemClickedEvent
  | VisualizeAnotherWayClickedEvent
  | VisualizerModalEvent
  | EmbeddingSetupStepSeenEvent
  | EmbeddingSetupClickEvent
  | EventsClickedEvent
  | AddDataModalOpenedEvent
  | AddDataModalTabEvent
  | DashboardFilterCreatedEvent
  | DashboardFilterMovedEvent
  | EmbedWizardEvent;
