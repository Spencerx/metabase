const { H } = cy;
import { SAMPLE_DATABASE } from "e2e/support/cypress_sample_database";
import type {
  DashboardDetails,
  StructuredQuestionDetails,
} from "e2e/support/helpers";

const { ORDERS_ID, ORDERS, PEOPLE_ID, PEOPLE } = SAMPLE_DATABASE;

const questionWith2TemporalBreakoutsDetails: StructuredQuestionDetails = {
  name: "Test question",
  query: {
    "source-table": ORDERS_ID,
    aggregation: [["count"]],
    breakout: [
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "year" },
      ],
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "month" },
      ],
    ],
  },
  display: "table",
  visualization_settings: {
    "table.pivot": false,
  },
};

const multiStageQuestionWith2TemporalBreakoutsDetails: StructuredQuestionDetails =
  {
    name: "Test question",
    query: {
      "source-query": questionWith2TemporalBreakoutsDetails.query,
      filter: [">", ["field", "count", { "base-type": "type/Integer" }], 0],
    },
  };

const questionWith2NumBinsBreakoutsDetails: StructuredQuestionDetails = {
  name: "Test question",
  query: {
    "source-table": ORDERS_ID,
    aggregation: [["count"]],
    breakout: [
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
          binning: { strategy: "num-bins", "num-bins": 10 },
        },
      ],
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
          binning: { strategy: "num-bins", "num-bins": 50 },
        },
      ],
    ],
  },
  display: "table",
  visualization_settings: {
    "table.pivot": false,
  },
};

const multiStageQuestionWith2NumBinsBreakoutsDetails: StructuredQuestionDetails =
  {
    name: "Test question",
    query: {
      "source-query": questionWith2NumBinsBreakoutsDetails.query,
      filter: [">", ["field", "count", { "base-type": "type/Integer" }], 0],
    },
  };

const questionWith2BinWidthBreakoutsDetails: StructuredQuestionDetails = {
  name: "Test question",
  query: {
    "source-table": PEOPLE_ID,
    aggregation: [["count"]],
    breakout: [
      [
        "field",
        PEOPLE.LATITUDE,
        {
          "base-type": "type/Float",
          binning: { strategy: "bin-width", "bin-width": 20 },
        },
      ],
      [
        "field",
        PEOPLE.LATITUDE,
        {
          "base-type": "type/Float",
          binning: { strategy: "bin-width", "bin-width": 10 },
        },
      ],
    ],
  },
  display: "table",
  visualization_settings: {
    "table.pivot": false,
  },
};

const multiStageQuestionWith2BinWidthBreakoutsDetails: StructuredQuestionDetails =
  {
    name: "Test question",
    query: {
      "source-query": questionWith2BinWidthBreakoutsDetails.query,
      filter: [">", ["field", "count", { "base-type": "type/Integer" }], 0],
    },
  };

const questionWith5TemporalBreakoutsDetails: StructuredQuestionDetails = {
  name: "Test question",
  query: {
    "source-table": ORDERS_ID,
    aggregation: [["count"]],
    breakout: [
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "year" },
      ],
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "quarter" },
      ],
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "month" },
      ],
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "week" },
      ],
      [
        "field",
        ORDERS.CREATED_AT,
        { "base-type": "type/DateTime", "temporal-unit": "day" },
      ],
    ],
    limit: 10,
  },
  display: "table",
  visualization_settings: {
    "table.pivot": false,
  },
};

const questionWith5NumBinsBreakoutsDetails: StructuredQuestionDetails = {
  name: "Test question",
  query: {
    "source-table": ORDERS_ID,
    aggregation: [["count"]],
    breakout: [
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
        },
      ],
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
          binning: { strategy: "default" },
        },
      ],
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
          binning: { strategy: "num-bins", "num-bins": 10 },
        },
      ],
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
          binning: { strategy: "num-bins", "num-bins": 50 },
        },
      ],
      [
        "field",
        ORDERS.TOTAL,
        {
          "base-type": "type/Float",
          binning: { strategy: "num-bins", "num-bins": 100 },
        },
      ],
    ],
    limit: 10,
  },
  display: "table",
  visualization_settings: {
    "table.pivot": false,
  },
};

const dashboardDetails: DashboardDetails = {
  parameters: [
    {
      id: "1",
      name: "Unit1",
      slug: "unit1",
      type: "temporal-unit",
      sectionId: "temporal-unit",
    },
    {
      id: "2",
      name: "Unit2",
      slug: "unit2",
      type: "temporal-unit",
      sectionId: "temporal-unit",
    },
  ],
  enable_embedding: true,
  embedding_params: {
    unit1: "enabled",
    unit2: "enabled",
  },
};

function getNestedQuestionDetails(cardId: number) {
  return {
    name: "Nested question",
    query: {
      "source-table": `card__${cardId}`,
    },
    visualization_settings: {
      "table.pivot": false,
    },
  };
}

// This is used in several places for the same query.
function assertTableDataForFilteredTemporalBreakouts() {
  H.assertTableData({
    columns: ["Created At: Year", "Created At: Month", "Count"],
    firstRows: [
      ["2023", "March 2023", "256"],
      ["2023", "April 2023", "238"],
      ["2023", "May 2023", "271"],
    ],
  });
  H.assertQueryBuilderRowCount(3);
}

describe("scenarios > question > multiple column breakouts", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsNormalUser();
    cy.intercept("POST", "/api/dataset").as("dataset");
    cy.intercept("POST", "/api/dataset/pivot").as("pivotDataset");
    cy.intercept("/api/dashboard/*/dashcard/*/card/*/query").as(
      "dashcardQuery",
    );
    cy.intercept("/api/public/dashboard/*/dashcard/*/card/*").as(
      "publicDashcardQuery",
    );
    cy.intercept("/api/embed/dashboard/*/dashcard/*/card/*").as(
      "embedDashcardQuery",
    );
  });

  describe("current stage", () => {
    describe("notebook", () => {
      it("should allow to create a query with multiple breakouts", () => {
        function testNewQueryWithBreakouts({
          tableName,
          columnName,
          bucketLabel,
          bucket1Name,
          bucket2Name,
        }: {
          tableName: string;
          columnName: string;
          bucketLabel: string;
          bucket1Name: string;
          bucket2Name: string;
        }) {
          H.startNewQuestion();
          H.entityPickerModal().within(() => {
            H.entityPickerModalTab("Tables").click();
            cy.findByText(tableName).click();
          });
          H.getNotebookStep("summarize")
            .findByText("Pick a function or metric")
            .click();
          H.popover().findByText("Count of rows").click();
          H.getNotebookStep("summarize")
            .findByText("Pick a column to group by")
            .click();
          H.popover()
            .findByLabelText(columnName)
            .findByLabelText(bucketLabel)
            .realHover()
            .click();
          // eslint-disable-next-line no-unsafe-element-filtering
          H.popover().last().findByText(bucket1Name).click();
          H.getNotebookStep("summarize")
            .findByTestId("breakout-step")
            .icon("add")
            .click();
          H.popover()
            .findByLabelText(columnName)
            .findByLabelText(bucketLabel)
            .click();
          // eslint-disable-next-line no-unsafe-element-filtering
          H.popover().last().findByText(bucket2Name).click();
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testNewQueryWithBreakouts({
          tableName: "Orders",
          columnName: "Created At",
          bucketLabel: "Temporal bucket",
          bucket1Name: "Year",
          bucket2Name: "Month",
        });
        H.assertQueryBuilderRowCount(49);

        cy.log("'num-bins' breakouts");
        testNewQueryWithBreakouts({
          tableName: "Orders",
          columnName: "Total",
          bucketLabel: "Binning strategy",
          bucket1Name: "10 bins",
          bucket2Name: "50 bins",
        });
        H.assertQueryBuilderRowCount(32);

        cy.log("'bin-width' breakouts");
        testNewQueryWithBreakouts({
          tableName: "People",
          columnName: "Latitude",
          bucketLabel: "Binning strategy",
          bucket1Name: "Bin every 10 degrees",
          bucket2Name: "Bin every 20 degrees",
        });
        H.assertQueryBuilderRowCount(6);
      });

      it("should allow to sort by breakout columns", () => {
        function testSortByBreakout({
          questionDetails,
          column1Name,
          column2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column2Name: string;
        }) {
          H.createQuestion(questionDetails, {
            visitQuestion: true,
          });
          H.openNotebook();
          H.getNotebookStep("summarize").findByText("Sort").click();
          H.popover().findByText(column1Name).click();
          H.getNotebookStep("sort").button("Change direction").click();
          H.getNotebookStep("sort").icon("add").click();
          H.popover().findByText(column2Name).click();
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testSortByBreakout({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column2Name: "Created At: Month",
        });
        H.assertTableData({
          columns: ["Created At: Year", "Created At: Month", "Count"],
          firstRows: [
            ["2026", "January 2026", "580"],
            ["2026", "February 2026", "543"],
          ],
        });

        cy.log("'num-bins' breakouts");
        testSortByBreakout({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column2Name: "Total: 50 bins",
        });
        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [
            ["140  –  160", "140  –  145", "306"],
            ["140  –  160", "145  –  150", "308"],
          ],
        });

        cy.log("'bin-width' breakouts");
        testSortByBreakout({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column2Name: "Latitude: 10°",
        });
        H.assertTableData({
          columns: ["Latitude: 20°", "Latitude: 10°", "Count"],
          firstRows: [
            ["60° N  –  80° N", "60° N  –  70° N", "51"],
            ["60° N  –  80° N", "70° N  –  80° N", "1"],
          ],
        });
      });
    });

    describe("summarize sidebar", () => {
      it("should allow to change buckets for multiple breakouts of the same column", () => {
        function testChangeBreakoutBuckets({
          questionDetails,
          columnPattern,
          bucketLabel,
          bucket1Name,
          bucket2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          columnPattern: RegExp;
          bucketLabel: string;
          bucket1Name: string;
          bucket2Name: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.summarize();
          cy.findByTestId("pinned-dimensions")
            .findAllByLabelText(columnPattern)
            .should("have.length", 2)
            .eq(0)
            .findByLabelText(bucketLabel)
            .realHover()
            .click();
          H.popover().findByText(bucket1Name).click();
          cy.wait("@dataset");
          cy.findByTestId("pinned-dimensions")
            .findAllByLabelText(columnPattern)
            .should("have.length", 2)
            .eq(1)
            .findByLabelText(bucketLabel)
            .realHover()
            .click();
          H.popover().findByText(bucket2Name).click();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testChangeBreakoutBuckets({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          columnPattern: /Created At/,
          bucketLabel: "Temporal bucket",
          bucket1Name: "Quarter",
          bucket2Name: "Week",
        });
        H.assertTableData({
          columns: ["Created At: Quarter", "Created At: Week", "Count"],
          firstRows: [["Q2 2022", "April 24, 2022 – April 30, 2022", "1"]],
        });

        cy.log("'num-bin' breakouts");
        testChangeBreakoutBuckets({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          columnPattern: /Total/,
          bucketLabel: "Binning strategy",
          bucket1Name: "10 bins",
          bucket2Name: "50 bins",
        });

        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [["-60  –  -40", "-50  –  -45", "1"]],
        });

        cy.log("'bin-width' breakouts");
        testChangeBreakoutBuckets({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          columnPattern: /Latitude/,
          bucketLabel: "Binning strategy",
          bucket1Name: "Bin every 1 degree",
          bucket2Name: "Bin every 0.1 degrees",
        });
        H.assertTableData({
          columns: ["Latitude: 1°", "Latitude: 0.1°", "Count"],
          firstRows: [["25° N  –  26° N", "25.7° N  –  25.8° N", "1"]],
        });
      });
    });

    describe("timeseries chrome", () => {
      it("should use the first breakout for the chrome in case there are multiple for this column", () => {
        H.createQuestion(questionWith2TemporalBreakoutsDetails, {
          visitQuestion: true,
        });

        cy.log("change the breakout");
        cy.findByTestId("timeseries-bucket-button")
          .should("contain.text", "Year")
          .click();
        H.popover().findByText("Quarter").click();
        cy.wait("@dataset");
        H.assertQueryBuilderRowCount(49);
        H.assertTableData({
          columns: ["Created At: Quarter", "Created At: Month", "Count"],
          firstRows: [["Q2 2022", "April 2022", "1"]],
        });

        cy.log("add a filter");
        cy.findByTestId("timeseries-filter-button")
          .should("contain.text", "All time")
          .click();
        H.popover().findByDisplayValue("All time").click();
        // eslint-disable-next-line no-unsafe-element-filtering
        H.popover().last().findByText("On").click();
        H.popover().within(() => {
          cy.findByLabelText("Date").clear().type("August 14, 2023");
          cy.button("Apply").click();
        });
        cy.wait("@dataset");
        H.assertQueryBuilderRowCount(1);
        H.assertTableData({
          columns: ["Created At: Quarter", "Created At: Month", "Count"],
          firstRows: [["Q3 2023", "August 2023", "9"]],
        });

        cy.log("change the filter");
        cy.findByTestId("timeseries-filter-button")
          .should("contain.text", "Aug 14")
          .click();
        H.popover().within(() => {
          cy.findByLabelText("Date").clear().type("August 14, 2022");
          cy.button("Apply").click();
        });
        cy.wait("@dataset");
        H.assertQueryBuilderRowCount(1);
        H.assertTableData({
          columns: ["Created At: Quarter", "Created At: Month", "Count"],
          firstRows: [["Q3 2022", "August 2022", "1"]],
        });
      });
    });

    describe("viz settings", () => {
      it("should be able to change formatting settings for breakouts of the same column", () => {
        function testColumnSettings({
          questionDetails,
          column1Name,
          column2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column2Name: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });

          cy.log("first breakout");
          tableHeaderClick(column1Name);
          H.popover().icon("gear").click();
          H.popover().findByDisplayValue(column1Name).clear().type("Breakout1");
          cy.get("body").click();

          cy.log("second breakout");
          tableHeaderClick(column2Name);
          H.popover().icon("gear").click();
          H.popover().findByDisplayValue(column2Name).clear().type("Breakout2");
          cy.get("body").click();
          H.assertTableData({ columns: ["Breakout1", "Breakout2", "Count"] });
        }

        cy.log("temporal breakouts");
        testColumnSettings({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column2Name: "Created At: Month",
        });

        cy.log("'num-bins' breakouts");
        testColumnSettings({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column2Name: "Total: 50 bins",
        });

        cy.log("'bin-width' breakouts");
        testColumnSettings({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column2Name: "Latitude: 10°",
        });
      });

      it("should be able to change pivot split settings when there are more than 2 breakouts", () => {
        function testPivotSplit({
          questionDetails,
          columnNamePattern,
        }: {
          questionDetails: StructuredQuestionDetails;
          columnNamePattern: RegExp;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });

          cy.log("change display and assert the default settings");
          H.openVizTypeSidebar();
          cy.findByTestId("chart-type-sidebar")
            .findByTestId("Pivot Table-button")
            .click();
          cy.wait("@pivotDataset");
          cy.findByTestId("pivot-table")
            .findAllByText(columnNamePattern)
            .should("have.length", 3);

          cy.log("move a column from rows to columns");
          H.openVizSettingsSidebar();
          H.moveDnDKitListElement("drag-handle", {
            startIndex: 2,
            dropIndex: 3,
          });
          cy.wait("@pivotDataset");
          cy.findByTestId("pivot-table")
            .findAllByText(columnNamePattern)
            .should("have.length", 2);

          cy.log("move a column from columns to rows");
          H.moveDnDKitListElement("drag-handle", {
            startIndex: 4,
            dropIndex: 1,
          });
          cy.wait("@pivotDataset");
          cy.findByTestId("pivot-table")
            .findAllByText(columnNamePattern)
            .should("have.length", 3);
        }

        cy.log("temporal breakouts");
        testPivotSplit({
          questionDetails: questionWith5TemporalBreakoutsDetails,
          columnNamePattern: /^Created At/,
        });

        cy.log("'num-bins' breakouts");
        testPivotSplit({
          questionDetails: questionWith5NumBinsBreakoutsDetails,
          columnNamePattern: /^Total: \d+ bins$/,
        });
      });

      it("should not be able to move columns items into measures and vice-versa", () => {
        H.createQuestion(questionWith5TemporalBreakoutsDetails, {
          visitQuestion: true,
        });

        const columnNamePattern = /^Created At/;

        cy.log("change display and assert the default settings");
        H.openVizTypeSidebar();
        cy.findByTestId("chart-type-sidebar")
          .findByTestId("Pivot Table-button")
          .click();
        cy.wait("@pivotDataset");
        cy.findByTestId("pivot-table")
          .findAllByText(columnNamePattern)
          .should("have.length", 3);

        cy.log("move an item from columns to measures");
        H.openVizSettingsSidebar();
        H.moveDnDKitListElement("drag-handle", {
          startIndex: 2,
          dropIndex: 5,
        });
        cy.findByTestId("pivot-table")
          .findAllByText(columnNamePattern)
          .should("have.length", 3);

        cy.log("move an item from measures to columns");
        H.moveDnDKitListElement("drag-handle", {
          startIndex: 5,
          dropIndex: 2,
        });
        cy.findByTestId("pivot-table")
          .findAllByText(columnNamePattern)
          .should("have.length", 3);
      });
    });

    describe("dashboards", () => {
      function setParametersAndAssertResults(queryAlias: string) {
        H.filterWidget().eq(0).click();
        H.popover().findByText("Quarter").click();
        cy.wait(queryAlias);
        H.filterWidget().eq(1).click();
        H.popover().findByText("Week").click();
        cy.wait(queryAlias);
        H.getDashboardCard().within(() => {
          cy.findByText("Created At: Quarter").should("be.visible");
          cy.findByText("Created At: Week").should("be.visible");
        });
      }

      it("should be able to use temporal-unit parameters with multiple temporal breakouts of a column", () => {
        cy.log("create dashboard");
        cy.signInAsAdmin();
        H.createQuestionAndDashboard({
          dashboardDetails,
          questionDetails: questionWith2TemporalBreakoutsDetails,
        }).then(({ body: { dashboard_id } }) => {
          cy.wrap(dashboard_id).as("dashboardId");
        });

        cy.log("visit dashboard");
        cy.signInAsNormalUser();
        H.visitDashboard("@dashboardId");
        cy.wait("@dashcardQuery");

        cy.log("add parameters");
        H.editDashboard();
        cy.findByTestId("fixed-width-filters").findByText("Unit1").click();
        H.getDashboardCard().findByText("Select…").click();
        H.popover().findByText("Created At: Year").click();
        cy.findByTestId("fixed-width-filters").findByText("Unit2").click();
        H.getDashboardCard().findByText("Select…").click();
        H.popover().findByText("Created At: Month").click();
        H.saveDashboard();
        cy.wait("@dashcardQuery");

        cy.log("set parameters and assert query results");
        setParametersAndAssertResults("@dashcardQuery");

        cy.log("drill-thru to the QB and assert query results");
        H.getDashboardCard().findByText("Test question").click();
        cy.wait("@dataset");
        H.tableInteractive().within(() => {
          cy.findByText("Created At: Quarter").should("be.visible");
          cy.findByText("Created At: Week").should("be.visible");
        });

        cy.log("set parameters in a public dashboard");
        cy.signInAsAdmin();
        cy.get<number>("@dashboardId").then(H.visitPublicDashboard);
        cy.wait("@publicDashcardQuery");
        setParametersAndAssertResults("@publicDashcardQuery");

        cy.log("set parameters in an embedded dashboard");
        cy.get<number>("@dashboardId").then((dashboardId) =>
          H.visitEmbeddedPage({
            resource: { dashboard: dashboardId },
            params: {},
          }),
        );
        cy.wait("@embedDashcardQuery");
        setParametersAndAssertResults("@embedDashcardQuery");
      });
    });
  });

  describe("previous stage", () => {
    describe("notebook", () => {
      it("should be able to add post-aggregation expressions for each breakout column", () => {
        function testDatePostAggregationExpression({
          questionDetails,
          expression1,
          expression2,
        }: {
          questionDetails: StructuredQuestionDetails;
          expression1: string;
          expression2: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.openNotebook();

          cy.log("add a post-aggregation expression for the first column");
          H.getNotebookStep("summarize").button("Custom column").click();
          H.enterCustomColumnDetails({
            formula: expression1,
            name: "Expression1",
            format: true,
            allowFastSet: true,
          });
          H.popover().button("Done").click();

          cy.log("add a post-aggregation expression for the second column");
          H.getNotebookStep("expression", { stage: 1 }).icon("add").click();
          H.enterCustomColumnDetails({
            formula: expression2,
            name: "Expression2",
            blur: true,
            allowFastSet: true,
          });
          H.popover().button("Done").click();

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testDatePostAggregationExpression({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          expression1: 'datetimeAdd([Created At: Year], 1, "year")',
          expression2: 'datetimeAdd([Created At: Month], 1, "month")',
        });
        H.assertTableData({
          columns: [
            "Created At: Year",
            "Created At: Month",
            "Count",
            "Expression1",
            "Expression2",
          ],
          firstRows: [
            [
              "2022",
              "April 2022",
              "1",
              "January 1, 2023, 12:00 AM",
              "May 1, 2022, 12:00 AM",
            ],
          ],
        });

        cy.log("'num-bins' breakouts");
        testDatePostAggregationExpression({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          expression1: "[Total: 10 bins] + 100",
          expression2: "[Total: 10 bins] + 200",
        });

        H.assertTableData({
          columns: [
            "Total: 10 bins",
            "Total: 50 bins",
            "Count",
            "Expression1",
            "Expression2",
          ],
          firstRows: [["-60  –  -40", "-50  –  -45", "1", "40", "140"]],
        });

        cy.log("'max-bins' breakouts");
        testDatePostAggregationExpression({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          expression1: "[Latitude: 20°] + 100",
          expression2: "[Latitude: 10°] + 200",
        });
        H.assertTableData({
          columns: [
            "Latitude: 20°",
            "Latitude: 10°",
            "Count",
            "Expression1",
            "Expression2",
          ],
          firstRows: [
            ["20° N  –  40° N", "20° N  –  30° N", "87", "120", "220"],
          ],
        });
      });

      it("should be able to add post-aggregation filters for each breakout column", () => {
        function addDateBetweenFilter({
          columnName,
          columnMinValue,
          columnMaxValue,
        }: {
          columnName: string;
          columnMinValue: string;
          columnMaxValue: string;
        }) {
          H.popover().within(() => {
            cy.findByText(columnName).click();
            cy.findByText("Fixed date range…").click();
            cy.findByText("Between").click();
            cy.findByLabelText("Start date").clear().type(columnMinValue);
            cy.findByLabelText("End date").clear().type(columnMaxValue);
            cy.button("Add filter").click();
          });
        }

        function testDatePostAggregationFilter({
          questionDetails,
          column1Name,
          column1MinValue,
          column1MaxValue,
          column2Name,
          column2MinValue,
          column2MaxValue,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column1MinValue: string;
          column1MaxValue: string;
          column2Name: string;
          column2MinValue: string;
          column2MaxValue: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.openNotebook();

          cy.log("add a filter for the first column");
          H.getNotebookStep("summarize").button("Filter").click();
          addDateBetweenFilter({
            columnName: column1Name,
            columnMinValue: column1MinValue,
            columnMaxValue: column1MaxValue,
          });

          cy.log("add a filter for the second column");
          H.getNotebookStep("filter", { stage: 1 }).icon("add").click();
          addDateBetweenFilter({
            columnName: column2Name,
            columnMinValue: column2MinValue,
            columnMaxValue: column2MaxValue,
          });

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        function addNumericBetweenFilter({
          columnName,
          columnMinValue,
          columnMaxValue,
        }: {
          columnName: string;
          columnMinValue: number;
          columnMaxValue: number;
        }) {
          H.popover().within(() => {
            cy.findByText(columnName).click();
            cy.findByPlaceholderText("Min")
              .clear()
              .type(String(columnMinValue));
            cy.findByPlaceholderText("Max")
              .clear()
              .type(String(columnMaxValue));
            cy.button("Add filter").click();
          });
        }

        function testNumericPostAggregationFilter({
          questionDetails,
          column1Name,
          column1MinValue,
          column1MaxValue,
          column2Name,
          column2MinValue,
          column2MaxValue,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column1MinValue: number;
          column1MaxValue: number;
          column2Name: string;
          column2MinValue: number;
          column2MaxValue: number;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.openNotebook();

          cy.log("add a filter for the first column");
          H.getNotebookStep("summarize").button("Filter").click();
          addNumericBetweenFilter({
            columnName: column1Name,
            columnMinValue: column1MinValue,
            columnMaxValue: column1MaxValue,
          });

          cy.log("add a filter for the second column");
          H.getNotebookStep("filter", { stage: 1 }).icon("add").click();
          addNumericBetweenFilter({
            columnName: column2Name,
            columnMinValue: column2MinValue,
            columnMaxValue: column2MaxValue,
          });

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal buckets");
        testDatePostAggregationFilter({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column1MinValue: "January 1, 2023",
          column1MaxValue: "December 31, 2023",
          column2Name: "Created At: Month",
          column2MinValue: "March 1, 2023",
          column2MaxValue: "May 31, 2023",
        });
        assertTableDataForFilteredTemporalBreakouts();

        cy.log("'num-bins' breakouts");
        testNumericPostAggregationFilter({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column1MinValue: 10,
          column1MaxValue: 50,
          column2Name: "Total: 50 bins",
          column2MinValue: 10,
          column2MaxValue: 50,
        });

        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [
            ["20  –  40", "20  –  25", "214"],
            ["20  –  40", "25  –  30", "396"],
          ],
        });
        H.assertQueryBuilderRowCount(7);

        cy.log("'bin-width' breakouts");
        testNumericPostAggregationFilter({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column1MinValue: 10,
          column1MaxValue: 50,
          column2Name: "Latitude: 10°",
          column2MinValue: 10,
          column2MaxValue: 50,
        });

        H.assertTableData({
          columns: ["Latitude: 20°", "Latitude: 10°", "Count"],
          firstRows: [
            ["20° N  –  40° N", "20° N  –  30° N", "87"],
            ["20° N  –  40° N", "30° N  –  40° N", "1,176"],
          ],
        });
        H.assertQueryBuilderRowCount(4);
      });

      it("should be able to add post-aggregation aggregations for each breakout column", () => {
        function testPostAggregationAggregation({
          questionDetails,
          column1Name,
          column2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column2Name: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.openNotebook();

          cy.log("add an aggregation for the first column");
          H.getNotebookStep("summarize").button("Summarize").click();
          H.popover().within(() => {
            cy.findByText("Minimum of ...").click();
            cy.findByText(column1Name).click();
          });

          cy.log("add an aggregation for the second column");
          H.getNotebookStep("summarize", { stage: 1 }).icon("add").click();
          H.popover().within(() => {
            cy.findByText("Maximum of ...").click();
            cy.findByText(column2Name).click();
          });

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testPostAggregationAggregation({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column2Name: "Created At: Month",
        });
        H.assertTableData({
          columns: ["Min of Created At: Year", "Max of Created At: Month"],
          firstRows: [["January 1, 2022, 12:00 AM", "April 1, 2026, 12:00 AM"]],
        });

        cy.log("'num-bins' breakouts");
        testPostAggregationAggregation({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column2Name: "Total: 50 bins",
        });
        H.assertTableData({
          columns: ["Min of Total: 10 bins", "Max of Total: 50 bins"],
          firstRows: [["-60", "155"]],
        });

        cy.log("'max-bins' breakouts");
        testPostAggregationAggregation({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column2Name: "Latitude: 10°",
        });

        H.assertTableData({
          columns: ["Min of Latitude: 20°", "Max of Latitude: 10°"],
          firstRows: [["20.00000000° N", "70.00000000° N"]],
        });
      });

      it("should be able to add post-aggregation breakouts for each breakout column", () => {
        function testPostAggregationBreakout({
          questionDetails,
          column1Name,
          column2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column2Name: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.openNotebook();

          cy.log("add an aggregation");
          H.getNotebookStep("summarize").button("Summarize").click();
          H.popover().findByText("Count of rows").click();

          cy.log("add a breakout for the first breakout column");
          H.getNotebookStep("summarize", { stage: 1 })
            .findByTestId("breakout-step")
            .findByText("Pick a column to group by")
            .click();
          H.popover().findByText(column1Name).click();

          cy.log("add a breakout for the second breakout column");
          H.getNotebookStep("summarize", { stage: 1 })
            .findByTestId("breakout-step")
            .icon("add")
            .click();
          H.popover().findByText(column2Name).click();

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testPostAggregationBreakout({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column2Name: "Created At: Month",
        });
        H.assertTableData({
          columns: ["Created At: Year", "Created At: Month", "Count"],
          firstRows: [["2022", "April 2022", "1"]],
        });

        cy.log("'num-bins' breakouts");
        testPostAggregationBreakout({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column2Name: "Total: 50 bins",
        });

        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [
            ["-60  –  -40", "-50  –  -45", "1"],
            ["0  –  20", "5  –  10", "1"],
          ],
        });

        cy.log("'max-bins' breakouts");
        testPostAggregationBreakout({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column2Name: "Latitude: 10°",
        });

        H.assertTableData({
          columns: ["Latitude: 20°", "Latitude: 10°", "Count"],
          firstRows: [
            ["20° N  –  40° N", "20° N  –  30° N", "1"],
            ["20° N  –  40° N", "30° N  –  40° N", "1"],
          ],
        });
      });
    });

    describe("filter picker", () => {
      it("should be able to add post-aggregation filters for each breakout in the filter picker", () => {
        function addDateBetweenFilter({
          columnName,
          columnMinValue,
          columnMaxValue,
        }: {
          columnName: string;
          columnMinValue: string;
          columnMaxValue: string;
        }) {
          H.filter();
          H.popover().within(() => {
            cy.findByText("Summaries").click();
            cy.findByText(columnName).click();
            cy.findByText("Fixed date range…").click();
            cy.findByText("Between").click();
            cy.findByLabelText("Start date").clear().type(columnMinValue);
            cy.findByLabelText("End date").clear().type(columnMaxValue);
            cy.button("Apply filter").click();
          });
        }

        function testDatePostAggregationFilter({
          questionDetails,
          column1Name,
          column1MinValue,
          column1MaxValue,
          column2Name,
          column2MinValue,
          column2MaxValue,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column1MinValue: string;
          column1MaxValue: string;
          column2Name: string;
          column2MinValue: string;
          column2MaxValue: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });

          cy.log("add a filter for the first column");
          addDateBetweenFilter({
            columnName: column1Name,
            columnMinValue: column1MinValue,
            columnMaxValue: column1MaxValue,
          });

          cy.log("add a filter for the second column");
          addDateBetweenFilter({
            columnName: column2Name,
            columnMinValue: column2MinValue,
            columnMaxValue: column2MaxValue,
          });

          cy.log("assert query results");
          cy.wait("@dataset");
        }

        function addNumericBetweenFilter({
          columnName,
          columnMinValue,
          columnMaxValue,
        }: {
          columnName: string;
          columnMinValue: number;
          columnMaxValue: number;
        }) {
          H.filter();
          H.popover().within(() => {
            cy.findByText("Summaries").click();
            cy.findByText(columnName).click();
            cy.findByPlaceholderText("Min")
              .clear()
              .type(String(columnMinValue));
            cy.findByPlaceholderText("Max")
              .clear()
              .type(String(columnMaxValue));
            cy.button("Apply filter").click();
          });
        }

        function testNumericPostAggregationFilter({
          questionDetails,
          column1Name,
          column1MinValue,
          column1MaxValue,
          column2Name,
          column2MinValue,
          column2MaxValue,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column1MinValue: number;
          column1MaxValue: number;
          column2Name: string;
          column2MinValue: number;
          column2MaxValue: number;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });

          cy.log("add a filter for the first column");
          addNumericBetweenFilter({
            columnName: column1Name,
            columnMinValue: column1MinValue,
            columnMaxValue: column1MaxValue,
          });

          cy.log("add a filter for the second column");
          addNumericBetweenFilter({
            columnName: column2Name,
            columnMinValue: column2MinValue,
            columnMaxValue: column2MaxValue,
          });

          cy.log("assert query results");
          cy.wait("@dataset");
        }

        cy.log("temporal buckets");
        testDatePostAggregationFilter({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column1MinValue: "January 1, 2023",
          column1MaxValue: "December 31, 2023",
          column2Name: "Created At: Month",
          column2MinValue: "March 1, 2023",
          column2MaxValue: "May 31, 2023",
        });
        assertTableDataForFilteredTemporalBreakouts();

        cy.log("'num-bins' breakouts");
        testNumericPostAggregationFilter({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column1MinValue: 10,
          column1MaxValue: 50,
          column2Name: "Total: 50 bins",
          column2MinValue: 10,
          column2MaxValue: 50,
        });
        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [
            ["20  –  40", "20  –  25", "214"],
            ["20  –  40", "25  –  30", "396"],
          ],
        });
        H.assertQueryBuilderRowCount(7);

        cy.log("'bin-width' breakouts");
        testNumericPostAggregationFilter({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column1MinValue: 10,
          column1MaxValue: 50,
          column2Name: "Latitude: 10°",
          column2MinValue: 10,
          column2MaxValue: 50,
        });
        H.assertTableData({
          columns: ["Latitude: 20°", "Latitude: 10°", "Count"],
          firstRows: [
            ["20° N  –  40° N", "20° N  –  30° N", "87"],
            ["20° N  –  40° N", "30° N  –  40° N", "1,176"],
          ],
        });
        H.assertQueryBuilderRowCount(4);
      });
    });

    describe("viz settings", () => {
      it("should be able to toggle the fields that correspond to breakout columns in the previous stage", () => {
        function toggleColumn(columnName: string, isVisible: boolean) {
          cy.findByTestId("chartsettings-sidebar").within(() => {
            cy.findByLabelText(columnName)
              .should(isVisible ? "not.be.checked" : "be.checked")
              .click();
            cy.findByLabelText(columnName).should(
              isVisible ? "be.checked" : "not.be.checked",
            );
          });
        }

        function testVisibleFields({
          questionDetails,
          queryColumn1Name,
          queryColumn2Name,
          tableColumn1Name,
          tableColumn2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          queryColumn1Name: string;
          queryColumn2Name: string;
          tableColumn1Name: string;
          tableColumn2Name: string;
        }) {
          H.createQuestion(questionDetails, { visitQuestion: true });
          H.assertTableData({
            columns: [tableColumn1Name, tableColumn2Name, "Count"],
          });

          H.openVizSettingsSidebar();
          cy.findByTestId("chartsettings-sidebar")
            .button("Add or remove columns")
            .click();
          toggleColumn(queryColumn1Name, false);
          cy.wait("@dataset");
          H.assertTableData({ columns: [tableColumn2Name, "Count"] });

          toggleColumn(queryColumn2Name, false);
          cy.wait("@dataset");
          H.assertTableData({ columns: ["Count"] });

          toggleColumn(queryColumn1Name, true);
          cy.wait("@dataset");
          H.assertTableData({ columns: ["Count", tableColumn1Name] });

          toggleColumn(queryColumn2Name, true);
          H.assertTableData({
            columns: ["Count", tableColumn1Name, tableColumn2Name],
          });
        }

        cy.log("temporal breakouts");
        testVisibleFields({
          questionDetails: multiStageQuestionWith2TemporalBreakoutsDetails,
          queryColumn1Name: "Created At: Year",
          queryColumn2Name: "Created At: Month",
          tableColumn1Name: "Created At: Year",
          tableColumn2Name: "Created At: Month",
        });

        cy.log("'num-bins' breakouts");
        testVisibleFields({
          questionDetails: multiStageQuestionWith2NumBinsBreakoutsDetails,
          queryColumn1Name: "Total: 10 bins",
          queryColumn2Name: "Total: 50 bins",
          tableColumn1Name: "Total: 10 bins",
          tableColumn2Name: "Total: 50 bins",
        });

        cy.log("'bin-width' breakouts");
        testVisibleFields({
          questionDetails: multiStageQuestionWith2BinWidthBreakoutsDetails,
          queryColumn1Name: "Latitude: 20°",
          queryColumn2Name: "Latitude: 10°",
          tableColumn1Name: "Latitude: 20°",
          tableColumn2Name: "Latitude: 10°",
        });
      });
    });
  });

  describe("data source", () => {
    describe("notebook", () => {
      it("should be able to add filters for each source column", () => {
        function addDateBetweenFilter({
          columnName,
          columnMinValue,
          columnMaxValue,
        }: {
          columnName: string;
          columnMinValue: string;
          columnMaxValue: string;
        }) {
          H.popover().within(() => {
            cy.findAllByText(columnName).click();
            cy.findByText("Fixed date range…").click();
            cy.findByText("Between").click();
            cy.findByLabelText("Start date").clear().type(columnMinValue);
            cy.findByLabelText("End date").clear().type(columnMaxValue);
            cy.button("Add filter").click();
          });
        }

        function testDatePostAggregationFilter({
          questionDetails,
          column1Name,
          column1MinValue,
          column1MaxValue,
          column2Name,
          column2MinValue,
          column2MaxValue,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column1MinValue: string;
          column1MaxValue: string;
          column2Name: string;
          column2MinValue: string;
          column2MaxValue: string;
        }) {
          H.createQuestion(questionDetails).then(({ body: card }) => {
            H.createQuestion(getNestedQuestionDetails(card.id), {
              visitQuestion: true,
            });
          });
          H.openNotebook();

          cy.log("add a filter for the first column");
          H.getNotebookStep("data").button("Filter").click();
          addDateBetweenFilter({
            columnName: column1Name,
            columnMinValue: column1MinValue,
            columnMaxValue: column1MaxValue,
          });

          cy.log("add a filter for the second column");
          H.getNotebookStep("filter").icon("add").click();
          addDateBetweenFilter({
            columnName: column2Name,
            columnMinValue: column2MinValue,
            columnMaxValue: column2MaxValue,
          });

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        function addNumericBetweenFilter({
          columnName,
          columnMinValue,
          columnMaxValue,
        }: {
          columnName: string;
          columnMinValue: number;
          columnMaxValue: number;
        }) {
          H.popover().within(() => {
            cy.findAllByText(columnName).click();
            cy.findByPlaceholderText("Min")
              .clear()
              .type(String(columnMinValue));
            cy.findByPlaceholderText("Max")
              .clear()
              .type(String(columnMaxValue));
            cy.button("Add filter").click();
          });
        }

        function testNumericPostAggregationFilter({
          questionDetails,
          column1Name,
          column1MinValue,
          column1MaxValue,
          column2Name,
          column2MinValue,
          column2MaxValue,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column1MinValue: number;
          column1MaxValue: number;
          column2Name: string;
          column2MinValue: number;
          column2MaxValue: number;
        }) {
          H.createQuestion(questionDetails).then(({ body: card }) => {
            H.createQuestion(getNestedQuestionDetails(card.id), {
              visitQuestion: true,
            });
          });
          H.openNotebook();

          cy.log("add a filter for the first column");
          H.getNotebookStep("data").button("Filter").click();
          addNumericBetweenFilter({
            columnName: column1Name,
            columnMinValue: column1MinValue,
            columnMaxValue: column1MaxValue,
          });

          cy.log("add a filter for the second column");
          H.getNotebookStep("filter").icon("add").click();
          addNumericBetweenFilter({
            columnName: column2Name,
            columnMinValue: column2MinValue,
            columnMaxValue: column2MaxValue,
          });

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal buckets");
        testDatePostAggregationFilter({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column1MinValue: "January 1, 2023",
          column1MaxValue: "December 31, 2023",
          column2Name: "Created At: Month",
          column2MinValue: "March 1, 2023",
          column2MaxValue: "May 31, 2023",
        });
        assertTableDataForFilteredTemporalBreakouts();

        cy.log("'num-bins' breakouts");
        testNumericPostAggregationFilter({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column1MinValue: 10,
          column1MaxValue: 50,
          column2Name: "Total: 50 bins",
          column2MinValue: 10,
          column2MaxValue: 50,
        });
        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [
            ["20  –  40", "20  –  25", "214"],
            ["20  –  40", "25  –  30", "396"],
          ],
        });
        H.assertQueryBuilderRowCount(7);

        cy.log("'bin-width' breakouts");
        testNumericPostAggregationFilter({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column1MinValue: 10,
          column1MaxValue: 50,
          column2Name: "Latitude: 10°",
          column2MinValue: 10,
          column2MaxValue: 50,
        });
        H.assertTableData({
          columns: ["Latitude: 20°", "Latitude: 10°", "Count"],
          firstRows: [
            ["20° N  –  40° N", "20° N  –  30° N", "87"],
            ["20° N  –  40° N", "30° N  –  40° N", "1,176"],
          ],
        });
        H.assertQueryBuilderRowCount(4);
      });

      it("should be able to add aggregations for each source column", () => {
        function testSourceAggregation({
          questionDetails,
          column1Name,
          column2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column2Name: string;
        }) {
          H.createQuestion(questionDetails).then(({ body: card }) => {
            H.createQuestion(getNestedQuestionDetails(card.id), {
              visitQuestion: true,
            });
          });
          H.openNotebook();

          cy.log("add an aggregation for the first column");
          H.getNotebookStep("data").button("Summarize").click();
          H.popover().within(() => {
            cy.findByText("Minimum of ...").click();
            cy.findAllByText(column1Name).click();
          });

          cy.log("add an aggregation for the second column");
          H.getNotebookStep("summarize").icon("add").click();
          H.popover().within(() => {
            cy.findByText("Maximum of ...").click();
            cy.findAllByText(column2Name).click();
          });

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testSourceAggregation({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column2Name: "Created At: Month",
        });
        H.assertTableData({
          columns: ["Min of Created At: Year", "Max of Created At: Month"],
          firstRows: [["January 1, 2022, 12:00 AM", "April 1, 2026, 12:00 AM"]],
        });

        cy.log("'num-bins' breakouts");
        testSourceAggregation({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column2Name: "Total: 50 bins",
        });
        H.assertTableData({
          columns: ["Min of Total: 10 bins", "Max of Total: 50 bins"],
          firstRows: [["-60", "155"]],
        });

        cy.log("'max-bins' breakouts");
        testSourceAggregation({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column2Name: "Latitude: 10°",
        });
        H.assertTableData({
          columns: ["Min of Latitude: 20°", "Max of Latitude: 10°"],
          firstRows: [["20.00000000° N", "70.00000000° N"]],
        });
      });

      it("should be able to add breakouts for each source column", () => {
        function testSourceBreakout({
          questionDetails,
          column1Name,
          column2Name,
        }: {
          questionDetails: StructuredQuestionDetails;
          column1Name: string;
          column2Name: string;
        }) {
          H.createQuestion(questionDetails).then(({ body: card }) => {
            H.createQuestion(getNestedQuestionDetails(card.id), {
              visitQuestion: true,
            });
          });
          H.openNotebook();

          cy.log("add an aggregation");
          H.getNotebookStep("data").button("Summarize").click();
          H.popover().findByText("Count of rows").click();

          cy.log("add a breakout for the first source column");
          H.getNotebookStep("summarize")
            .findByTestId("breakout-step")
            .findByText("Pick a column to group by")
            .click();
          H.popover().findAllByText(column1Name).click();

          cy.log("add a breakout for the second source column");
          H.getNotebookStep("summarize")
            .findByTestId("breakout-step")
            .icon("add")
            .click();
          H.popover().findAllByText(column2Name).click();

          cy.log("assert query results");
          H.visualize();
          cy.wait("@dataset");
        }

        cy.log("temporal breakouts");
        testSourceBreakout({
          questionDetails: questionWith2TemporalBreakoutsDetails,
          column1Name: "Created At: Year",
          column2Name: "Created At: Month",
        });
        H.assertTableData({
          columns: ["Created At: Year", "Created At: Month", "Count"],
          firstRows: [["2022", "April 2022", "1"]],
        });

        cy.log("'num-bins' breakouts");
        testSourceBreakout({
          questionDetails: questionWith2NumBinsBreakoutsDetails,
          column1Name: "Total: 10 bins",
          column2Name: "Total: 50 bins",
        });
        H.assertTableData({
          columns: ["Total: 10 bins", "Total: 50 bins", "Count"],
          firstRows: [
            ["-60  –  -40", "-50  –  -45", "1"],
            ["0  –  20", "5  –  10", "1"],
          ],
        });

        cy.log("'max-bins' breakouts");
        testSourceBreakout({
          questionDetails: questionWith2BinWidthBreakoutsDetails,
          column1Name: "Latitude: 20°",
          column2Name: "Latitude: 10°",
        });
        H.assertTableData({
          columns: ["Latitude: 20°", "Latitude: 10°", "Count"],
          firstRows: [
            ["20° N  –  40° N", "20° N  –  30° N", "1"],
            ["20° N  –  40° N", "30° N  –  40° N", "1"],
          ],
        });
      });
    });

    describe("viz settings", () => {
      it("should be able to toggle the fields that correspond to breakout columns in the source card", () => {
        function toggleColumn(columnName: string, isVisible: boolean) {
          cy.findByTestId("chartsettings-sidebar").within(() => {
            cy.findAllByLabelText(columnName)
              .should(isVisible ? "not.be.checked" : "be.checked")
              .click();
            cy.findAllByLabelText(columnName).should(
              isVisible ? "be.checked" : "not.be.checked",
            );
          });
        }

        function testVisibleFields({
          questionDetails,
          columnName,
        }: {
          questionDetails: StructuredQuestionDetails;
          columnName: string;
        }) {
          H.createQuestion(questionDetails).then(({ body: card }) => {
            H.createQuestion(getNestedQuestionDetails(card.id), {
              visitQuestion: true,
            });
          });
          const columnNameYear = columnName + ": Year";
          const columnNameMonth = columnName + ": Month";
          H.assertTableData({
            columns: [columnNameYear, columnNameMonth, "Count"],
          });

          H.openVizSettingsSidebar();
          cy.findByTestId("chartsettings-sidebar")
            .button("Add or remove columns")
            .click();
          toggleColumn(columnNameYear, false);
          cy.wait("@dataset");
          H.assertTableData({ columns: [columnNameMonth, "Count"] });

          toggleColumn(columnNameMonth, false);
          cy.wait("@dataset");
          H.assertTableData({ columns: ["Count"] });

          toggleColumn(columnNameYear, true);
          cy.wait("@dataset");
          H.assertTableData({ columns: ["Count", columnNameYear] });

          toggleColumn(columnNameMonth, true);
          H.assertTableData({
            columns: ["Count", columnNameYear, columnNameMonth],
          });
        }

        cy.log("temporal breakouts");
        testVisibleFields({
          questionDetails: multiStageQuestionWith2TemporalBreakoutsDetails,
          columnName: "Created At",
        });
      });
    });
  });
});

function tableHeaderClick(
  columnName: string,
  { columnIndex = 0 }: { columnIndex?: number } = {},
) {
  // eslint-disable-next-line no-unsafe-element-filtering
  H.tableInteractive()
    .findAllByText(columnName)
    .eq(columnIndex)
    .trigger("mousedown");

  // eslint-disable-next-line no-unsafe-element-filtering
  H.tableInteractive()
    .findAllByText(columnName)
    .eq(columnIndex)
    .trigger("mouseup");
}
