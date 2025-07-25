import { createMockMetadata } from "__support__/metadata";
import { createQuery } from "metabase-lib/test-helpers";
import type { DatabaseFeature } from "metabase-types/api";
import { createSampleDatabase } from "metabase-types/api/mocks/presets";

import { complete } from "./__support__";
import { type Options, suggestAggregations } from "./aggregations";

describe("suggestAggregations", () => {
  function setup({
    expressionMode = "aggregation",
    features = [],
  }: Partial<Options> & {
    features?: DatabaseFeature[];
  }) {
    const metadata = createMockMetadata({
      databases: [
        createSampleDatabase({
          features,
        }),
      ],
    });
    const query = createQuery({ metadata });
    const source = suggestAggregations({
      expressionMode,
      query,
      metadata,
    });

    return function (doc: string) {
      return complete(source, doc);
    };
  }

  describe("expressionMode = expression", () => {
    const expressionMode = "expression";

    it("should not suggest aggregations", () => {
      const completer = setup({ expressionMode });
      const results = completer("Coun|");
      expect(results).toEqual(null);
    });
  });

  describe("expressionMode = boolean", () => {
    const expressionMode = "filter";

    it("should not suggest aggregations", () => {
      const completer = setup({ expressionMode });
      const results = completer("Coun|");
      expect(results).toEqual(null);
    });
  });

  describe("expressionMode = aggregation", () => {
    const expressionMode = "aggregation";

    const RESULTS = {
      from: 0,
      to: 4,
      filter: false,
      options: [
        {
          apply: expect.any(Function),
          displayLabel: "Count",
          icon: "function",
          label: "Count",
          matches: [[0, 3]],
          type: "aggregation",
        },
        {
          apply: expect.any(Function),
          displayLabel: "CountIf",
          icon: "function",
          label: "CountIf",
          matches: [[0, 3]],
          type: "aggregation",
        },
        {
          apply: expect.any(Function),
          displayLabel: "CumulativeCount",
          icon: "function",
          label: "CumulativeCount",
          matches: [
            [0, 1],
            [3, 3],
            [10, 13],
          ],
          type: "aggregation",
        },
        {
          displayLabel: "CumulativeSum",
          label: "CumulativeSum",
          matches: [
            [0, 1],
            [3, 3],
            [11, 11],
          ],
          icon: "function",
          type: "aggregation",
          apply: expect.any(Function),
        },
      ],
    };

    const RESULTS_NO_TEMPLATE = {
      ...RESULTS,
      options: RESULTS.options.map((option) => ({
        ...option,
        apply: undefined,
      })),
    };

    it("should suggest aggregations", () => {
      const completer = setup({ expressionMode, features: [] });
      const results = completer("Coun|");
      expect(results).toEqual(RESULTS);
    });

    it("should not suggest unsupported aggregations", () => {
      const completer = setup({ expressionMode });
      const results = completer("StandardDev|");
      expect(results).toEqual({
        from: 0,
        to: 11,
        options: [],
        filter: false,
      });
    });

    it("should suggest supported aggregations", () => {
      const completer = setup({
        expressionMode,
        features: ["standard-deviation-aggregations"],
      });
      const results = completer("StandardDev|");
      expect(results).toEqual({
        from: 0,
        to: 11,
        filter: false,
        options: [
          {
            label: "StandardDeviation",
            displayLabel: "StandardDeviation",
            matches: [[0, 10]],
            type: "aggregation",
            icon: "function",
            apply: expect.any(Function),
          },
        ],
      });
    });

    it("should suggest aggregations, inside a word", () => {
      const completer = setup({ expressionMode });
      const results = completer("Cou|n");
      expect(results).toEqual(RESULTS);
    });

    it("should suggest aggregatoins, before parenthesis", () => {
      const cases = [
        "Coun|(",
        "Cou|n(",
        "Coun|()",
        "Cou|n()",
        "Coun|([Foo]",
        "Cou|n([Foo]",
        "Coun|([Foo])",
        "Cou|n([Foo])",
        "Coun| (",
        "Cou|n (",
        "Coun| ()",
        "Cou|n ()",
        "Coun| ([Foo]",
        "Cou|n ([Foo]",
        "Coun| ([Foo])",
        "Cou|n ([Foo])",
      ];
      for (const doc of cases) {
        const completer = setup({ expressionMode });
        const results = completer(doc);
        expect(results).toEqual(RESULTS_NO_TEMPLATE);
      }
    });
  });
});
