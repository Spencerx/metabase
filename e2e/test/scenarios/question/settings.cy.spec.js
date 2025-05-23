const { H } = cy;
import { SAMPLE_DB_ID } from "e2e/support/cypress_data";
import { SAMPLE_DATABASE } from "e2e/support/cypress_sample_database";

const { ORDERS, ORDERS_ID, PRODUCTS, PRODUCTS_ID } = SAMPLE_DATABASE;

describe("scenarios > question > settings", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
  });

  describe("column settings", () => {
    it("should allow you to remove a column and add two foreign columns", () => {
      // oddly specific test inspired by https://github.com/metabase/metabase/issues/11499

      // get a really wide window, so we don't need to mess with scrolling the table horizontally
      cy.viewport(1600, 800);

      H.openOrdersTable();
      H.openVizSettingsSidebar();

      // wait for settings sidebar to open
      cy.findByTestId("sidebar-left").invoke("width").should("be.gt", 350);

      cy.findByTestId("sidebar-content").as("tableOptions");

      cy.findByRole("button", { name: "Add or remove columns" }).click();

      // remove Total column
      cy.get("@tableOptions")
        .findByLabelText("Total")
        .should("be.checked")
        .click();

      // Add people.category
      cy.get("@tableOptions")
        .findByLabelText("Category")
        .should("not.be.checked")
        .click();

      // wait a Category value to appear in the table, so we know the query completed
      cy.findByTestId("visualization-root").contains("Widget");

      // Add people.ean
      cy.get("@tableOptions")
        .findByLabelText("Ean")
        .should("not.be.checked")
        .click();

      // wait a Ean value to appear in the table, so we know the query completed
      cy.findByTestId("visualization-root").contains("8833419218504");

      // confirm that the table contains the right columns
      H.tableInteractive().as("table");
      cy.get("@table").contains("Product → Category");
      cy.get("@table").contains("Product → Ean");
      cy.get("@table").contains("Total").should("not.exist");
    });

    it("should allow you to re-order columns even when one has been removed (metabase #14238, #29287)", () => {
      cy.viewport(1600, 800);

      H.visitQuestionAdhoc({
        dataset_query: {
          database: SAMPLE_DB_ID,
          query: {
            "source-table": ORDERS_ID,
            joins: [
              {
                fields: "all",
                alias: "Products",
                "source-table": PRODUCTS_ID,
                strategy: "left-join",
                condition: [
                  "=",
                  ["field", ORDERS.PRODUCT_ID, {}],
                  ["field", PRODUCTS.ID, { "join-alias": "Products" }],
                ],
              },
            ],
          },
          type: "query",
        },
      });
      H.openVizSettingsSidebar();

      cy.findByTestId("Subtotal-hide-button").click();
      cy.findByTestId("Tax-hide-button").click();

      getSidebarColumns().eq("5").as("total").contains("Total");

      H.moveDnDKitElement(cy.get("@total"), { vertical: -100 });

      getSidebarColumns().eq("3").should("contain.text", "Total");

      getVisibleSidebarColumns()
        .eq("11")
        .as("title")
        .should("have.text", "Products → Title");

      cy.findByTestId("chartsettings-list-container").scrollTo("top");
      cy.findByTestId("chartsettings-list-container").should(([$el]) => {
        expect($el.scrollTop).to.eql(0);
      });

      H.moveDnDKitElement(cy.get("@title"), { vertical: 15 });

      cy.findByTestId("chartsettings-list-container").should(([$el]) => {
        expect($el.scrollTop).to.be.greaterThan(0);
      });
    });

    it("should preserve correct order of columns after column removal via sidebar (metabase#13455)", () => {
      cy.viewport(2000, 1600);
      // Orders join Products
      H.visitQuestionAdhoc({
        dataset_query: {
          type: "query",
          query: {
            "source-table": ORDERS_ID,
            joins: [
              {
                fields: "all",
                "source-table": PRODUCTS_ID,
                condition: [
                  "=",
                  [
                    "field",
                    ORDERS.PRODUCT_ID,
                    {
                      "base-type": "type/Integer",
                    },
                  ],
                  [
                    "field",
                    PRODUCTS.ID,
                    {
                      "base-type": "type/BigInteger",
                      "join-alias": "Products",
                    },
                  ],
                ],
                alias: "Products",
              },
            ],
            limit: 5,
          },
          database: SAMPLE_DB_ID,
        },
        display: "table",
      });

      H.openVizSettingsSidebar();

      getSidebarColumns()
        .eq("12")
        .as("prod-category")
        .contains(/Products? → Category/);

      // Drag and drop this column between "Tax" and "Discount" (index 5 in @sidebarColumns array)
      H.moveDnDKitElement(cy.get("@prod-category"), { vertical: -360 });

      refreshResultsInHeader();

      findColumnAtIndex("Products → Category", 5);

      // Remove "Total"
      hideColumn("Total");

      cy.findByTestId("query-builder-main")
        .findByText("117.03")
        .should("not.exist");

      findColumnAtIndex("Products → Category", 5);

      // We need to do some additional checks. Please see:
      // https://github.com/metabase/metabase/pull/21338#pullrequestreview-928807257

      // Add "Address"
      cy.findByRole("button", { name: "Add or remove columns" }).click();
      cy.findByLabelText("Address").should("not.be.checked").click();

      cy.wait("@dataset");

      cy.findByRole("button", { name: "Done picking columns" }).click();

      findColumnAtIndex("User → Address", -1).as("user-address");

      // Move it one place up
      H.moveDnDKitElement(cy.get("@user-address"), { vertical: -100 });

      findColumnAtIndex("User → Address", -3);

      /**
       * Helper functions related to THIS test only
       */

      function findColumnAtIndex(column_name, index) {
        // eslint-disable-next-line no-unsafe-element-filtering
        return getVisibleSidebarColumns().eq(index).contains(column_name);
      }
    });

    it("should be okay showing an empty joined table (metabase#29140)", () => {
      // Orders join Products
      H.visitQuestionAdhoc({
        dataset_query: {
          type: "query",
          query: {
            "source-table": ORDERS_ID,
            fields: [
              ["field", ORDERS.PRODUCT_ID, { "base-type": "type/Integer" }],
              ["field", ORDERS.SUBTOTAL, { "base-type": "type/Float" }],
            ],
            limit: 5,
          },
          database: SAMPLE_DB_ID,
        },
        display: "table",
      });

      H.openVizSettingsSidebar();

      cy.findByRole("button", { name: "Add or remove columns" }).click();
      cy.findByLabelText("Name").should("not.be.checked").click();
      cy.findByRole("button", { name: "Done picking columns" }).click();

      // Remove "Product ID"
      hideColumn("Product ID");

      // Remove "Subtotal"
      hideColumn("Subtotal");

      // Remove "Name"
      hideColumn("User → Name");
      cy.findByTestId("query-builder-main").findByText(
        "Every field is hidden right now",
      );
    });

    it("should change to column formatting when sidebar is already open (metabase#16043)", () => {
      H.visitQuestionAdhoc({
        dataset_query: {
          type: "query",
          query: { "source-table": ORDERS_ID },
          database: SAMPLE_DB_ID,
        },
      });

      H.openVizSettingsSidebar(); // open settings sidebar
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Conditional Formatting"); // confirm it's open

      H.tableHeaderClick("Subtotal"); // open subtotal column header actions

      H.popover().icon("gear").click(); // open subtotal column settings

      //cy.findByText("Table options").should("not.exist"); // no longer displaying the top level settings
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Separator style"); // shows subtotal column settings

      cy.findByTestId("head-crumbs-container").findByText("Orders").click(); //Dismiss popover

      H.tableHeaderClick("Created At"); // open created_at column header actions

      H.popover().within(() => {
        cy.icon("gear").click(); // open created_at column settings
      });
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Date style"); // shows created_at column settings
    });

    it("should respect renamed column names in the settings sidebar (metabase#18476)", () => {
      const newColumnTitle = "Pre-tax";

      const questionDetails = {
        dataset_query: {
          database: SAMPLE_DB_ID,
          query: { "source-table": ORDERS_ID },
          type: "query",
        },
        display: "table",
        visualization_settings: {
          column_settings: {
            [`["ref",["field",${ORDERS.SUBTOTAL},null]]`]: {
              column_title: newColumnTitle,
            },
          },
        },
      };

      H.visitQuestionAdhoc(questionDetails);

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText(newColumnTitle);

      H.openVizSettingsSidebar();

      H.sidebar().findByText(newColumnTitle);
    });

    it("should respect symbol settings for all currencies", () => {
      H.openOrdersTable();
      H.openVizSettingsSidebar();

      getSidebarColumns()
        .eq("4")
        .within(() => {
          cy.icon("ellipsis").click({ force: true });
        });

      cy.findByDisplayValue("Normal").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Currency").click();

      cy.findByDisplayValue("US Dollar").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Bitcoin").click();

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("In every table cell").click();

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("₿ 2.07");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("₿ 6.10");
    });

    it("should show all options without text overflowing", () => {
      const longName =
        "SuperLongColumnNameSuperLongColumnNameSuperLongColumnNameSuperLongColumnNameSuperLongColumnName";
      H.createNativeQuestion(
        {
          name: "Orders Model",
          native: {
            query: `SELECT total as "${longName}" FROM ORDERS`,
          },
        },
        { visitQuestion: true },
      );

      H.openVizSettingsSidebar();

      H.sidebar()
        .findByRole("listitem")
        .within(() => {
          cy.findByLabelText("ellipsis icon").should("be.visible");
          cy.findByLabelText("grabber icon").should("be.visible");
          cy.findByLabelText("eye_outline icon").should("be.visible");
          cy.findByText(longName).should("be.visible");
        });
    });

    it("should show all series settings without text overflowing (metabase#52975)", () => {
      const regularColumnName = "regular column";
      const longColumnName1 =
        "very very very very very very very very very very very long column 1";
      const longColumnName2 =
        "very very very very very very very very very very very long column 2";

      H.createNativeQuestion(
        {
          name: "52975",
          native: {
            query: `select 'foo' x, 10 "${regularColumnName}", 20 "${longColumnName1}", 20 "${longColumnName2}"`,
          },
          display: "bar",
        },
        { visitQuestion: true },
      );

      H.openVizSettingsSidebar();

      H.sidebar()
        .findAllByTestId("chartsettings-field-picker")
        .eq(3)
        .within(() => {
          cy.findByTestId("color-selector-button").should("be.visible");
          cy.findByLabelText("chevrondown icon").should("not.exist");
          cy.findByLabelText("ellipsis icon").should("be.visible");
          cy.findByLabelText("grabber icon").should("be.visible");
          cy.get("input").should("have.value", longColumnName2);
          cy.findByLabelText("close icon").should("be.visible");

          cy.findByTestId(`remove-${longColumnName2}`).click();
        });

      H.sidebar()
        .findAllByTestId("chartsettings-field-picker")
        .eq(2)
        .within(() => {
          cy.findByTestId("color-selector-button").should("be.visible");
          cy.findByLabelText("chevrondown icon").should("be.visible");
          cy.findByLabelText("ellipsis icon").should("be.visible");
          cy.findByLabelText("grabber icon").should("be.visible");
          cy.get("input").should("have.value", longColumnName1);
          cy.findByLabelText("close icon").should("be.visible");

          cy.findByTestId(`remove-${longColumnName1}`).click();
        });

      H.sidebar()
        .findAllByTestId("chartsettings-field-picker")
        .eq(1)
        .within(() => {
          cy.findByTestId("color-selector-button").should("be.visible");
          cy.findByLabelText("chevrondown icon").should("be.visible");
          cy.findByLabelText("ellipsis icon").should("be.visible");
          cy.findByLabelText("grabber icon").should("not.exist");
          cy.get("input").should("have.value", regularColumnName);
          cy.findByLabelText("close icon").should("not.exist");
        });
    });

    it.skip("should allow hiding and showing aggregated columns with a post-aggregation custom column (metabase#22563)", () => {
      // products joined to orders with breakouts on 3 product columns followed by a custom column
      H.createQuestion(
        {
          name: "repro 22563",
          query: {
            "source-query": {
              "source-table": ORDERS_ID,
              joins: [
                {
                  alias: "Products",
                  condition: [
                    "=",
                    ["field", ORDERS.PRODUCT_ID, null],
                    [
                      "field",
                      PRODUCTS.ID,
                      {
                        "join-alias": "Products",
                      },
                    ],
                  ],
                  "source-table": PRODUCTS_ID,
                },
              ],
              aggregation: [["count"]],
              breakout: [
                [
                  "field",
                  PRODUCTS.CATEGORY,
                  {
                    "base-type": "type/Text",
                    "join-alias": "Products",
                  },
                ],
                [
                  "field",
                  PRODUCTS.TITLE,
                  {
                    "base-type": "type/Text",
                    "join-alias": "Products",
                  },
                ],
                [
                  "field",
                  PRODUCTS.VENDOR,
                  {
                    "base-type": "type/Text",
                    "join-alias": "Products",
                  },
                ],
              ],
            },
            expressions: {
              two: ["+", 1, 1],
            },
          },
        },
        { visitQuestion: true },
      );

      const columnNames = [
        "Products → Category",
        "Products → Title",
        "Products → Vendor",
        "Count",
        "two",
      ];

      H.tableInteractive().within(() => {
        columnNames.forEach((text) => cy.findByText(text).should("be.visible"));
      });

      H.openVizSettingsSidebar();

      cy.findByTestId("chartsettings-sidebar").within(() => {
        columnNames.forEach((text) => cy.findByText(text).should("be.visible"));
        cy.findByText("More Columns").should("not.exist");

        cy.icon("eye_outline").first().click();

        cy.findByText("More columns").should("be.visible");

        // disable the first column
        cy.findByTestId("disabled-columns")
          .findByText("Products → Category")
          .should("be.visible");
        cy.findByTestId("visible-columns")
          .findByText("Products → Category")
          .should("not.exist");
      });

      H.tableInteractive().within(() => {
        // the query should not have changed
        cy.icon("play").should("not.exist");
        cy.findByText("Products → Category").should("not.exist");
      });

      cy.findByTestId("chartsettings-sidebar").within(() => {
        cy.icon("add").click();
        // re-enable the first column
        cy.findByText("More columns").should("not.exist");
        cy.findByTestId("visible-columns")
          .findByText("Products → Category")
          .should("be.visible");
      });

      H.tableInteractive().within(() => {
        // the query should not have changed
        cy.icon("play").should("not.exist");
        cy.findByText("Products → Category").should("be.visible");
      });
    });
  });

  describe("resetting state", () => {
    it("should reset modal state when navigating away", () => {
      // create a question and add it to a modal
      H.openOrdersTable();

      cy.findByTestId("qb-header").contains("Save").click();
      cy.findByTestId("save-question-modal").within(() => {
        cy.findByLabelText(/Where do you want to save this/).click();
      });
      H.pickEntity({ tab: "Browse", path: ["Our analytics"], select: false });
      H.entityPickerModal().findByText("Select this collection").click();
      cy.findByTestId("save-question-modal").findByText("Save").click();
      H.modal().findByText("Yes please!").click();
      H.entityPickerModal().within(() => {
        cy.findByText("Orders in a dashboard").click();
        cy.findByText("Cancel").click();
      });

      // create a new question to see if the "add to a dashboard" modal is still there
      H.openNavigationSidebar();
      H.browseDatabases().click();

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.contains("Sample Database").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.contains("Orders").click();

      // This next assertion might not catch bugs where the modal displays after
      // a quick delay. With the previous presentation of this bug, the modal
      // was immediately visible, so I'm not going to add any waits.
      H.modal().should("not.exist");
    });
  });
});

function refreshResultsInHeader() {
  cy.findByTestId("qb-header").button("Refresh").click();
  cy.wait("@dataset");
}

function getSidebarColumns() {
  return cy
    .findByTestId("chart-settings-table-columns")
    .scrollIntoView()
    .should("be.visible")
    .findAllByRole("listitem");
}

function getVisibleSidebarColumns() {
  return cy.findByTestId("visible-columns").findAllByRole("listitem");
}

function hideColumn(name) {
  H.sidebar()
    .findByTestId(`draggable-item-${name}`)
    .icon("eye_outline")
    .click({ force: true });
}
