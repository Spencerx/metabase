/* istanbul ignore file */

import { createMockMetadata } from "__support__/metadata";
import { checkNotNull } from "metabase/lib/types";
import * as Lib from "metabase-lib";
import type Metadata from "metabase-lib/v1/metadata/Metadata";
import type {
  DatabaseId,
  DatasetColumn,
  DatasetQuery,
  RowValue,
  TableId,
} from "metabase-types/api";
import {
  ORDERS_ID,
  createSampleDatabase,
} from "metabase-types/api/mocks/presets";

const SAMPLE_DATABASE = createSampleDatabase();

const SAMPLE_METADATA = createMockMetadata({ databases: [SAMPLE_DATABASE] });

export { SAMPLE_DATABASE, SAMPLE_METADATA };

type MetadataProviderOpts = {
  databaseId?: DatabaseId;
  metadata?: Metadata;
};

function createMetadataProvider({
  databaseId = SAMPLE_DATABASE.id,
  metadata = SAMPLE_METADATA,
}: MetadataProviderOpts = {}) {
  return Lib.metadataProvider(databaseId, metadata);
}

export const DEFAULT_QUERY: DatasetQuery = {
  database: SAMPLE_DATABASE.id,
  type: "query",
  query: {
    "source-table": ORDERS_ID,
  },
};

type QueryOpts = MetadataProviderOpts & {
  query?: DatasetQuery;
};

export function createQuery({
  databaseId = SAMPLE_DATABASE.id,
  metadata = SAMPLE_METADATA,
  query = DEFAULT_QUERY,
}: QueryOpts = {}) {
  const metadataProvider = createMetadataProvider({ databaseId, metadata });
  return Lib.fromLegacyQuery(databaseId, metadataProvider, query);
}

export const columnFinder =
  (query: Lib.Query, columns: Lib.ColumnMetadata[]) =>
  (
    tableName: string | undefined | null,
    columnName: string,
  ): Lib.ColumnMetadata => {
    const column = columns.find((column) => {
      const displayInfo = Lib.displayInfo(query, 0, column);

      // for non-table columns - aggregations, custom columns
      if (!displayInfo.table || tableName == null) {
        return displayInfo.name === columnName;
      }

      return (
        displayInfo.table.name === tableName && displayInfo.name === columnName
      );
    });

    if (!column) {
      throw new Error(`Could not find ${tableName}.${columnName}`);
    }

    return column;
  };

export const findBinningStrategy = (
  query: Lib.Query,
  column: Lib.ColumnMetadata,
  bucketName: string,
) => {
  if (bucketName === "Don't bin") {
    return null;
  }
  const buckets = Lib.availableBinningStrategies(query, 0, column);
  const bucket = buckets.find(
    (bucket) => Lib.displayInfo(query, 0, bucket).displayName === bucketName,
  );
  if (!bucket) {
    throw new Error(`Could not find binning strategy ${bucketName}`);
  }
  return bucket;
};

export const findTemporalBucket = (
  query: Lib.Query,
  column: Lib.ColumnMetadata,
  bucketName: string,
) => {
  if (bucketName === "Don't bin") {
    return null;
  }

  const buckets = Lib.availableTemporalBuckets(query, 0, column);
  const bucket = buckets.find(
    (bucket) => Lib.displayInfo(query, 0, bucket).displayName === bucketName,
  );
  if (!bucket) {
    throw new Error(`Could not find temporal bucket ${bucketName}`);
  }
  return bucket;
};

export const findAggregationOperator = (
  query: Lib.Query,
  operatorShortName: string,
) => {
  const operators = Lib.availableAggregationOperators(query, 0);
  const operator = operators.find(
    (operator) =>
      Lib.displayInfo(query, 0, operator).shortName === operatorShortName,
  );
  if (!operator) {
    throw new Error(`Could not find aggregation operator ${operatorShortName}`);
  }
  return operator;
};

export const findSegment = (query: Lib.Query, segmentName: string) => {
  const stageIndex = 0;
  const segment = Lib.availableSegments(query, stageIndex).find(
    (segment) =>
      Lib.displayInfo(query, stageIndex, segment).displayName === segmentName,
  );
  if (!segment) {
    throw new Error(`Could not find segment ${segmentName}`);
  }
  return segment;
};

function withTemporalBucketAndBinningStrategy(
  query: Lib.Query,
  column: Lib.ColumnMetadata,
  temporalBucketName = "Don't bin",
  binningStrategyName = "Don't bin",
) {
  return Lib.withTemporalBucket(
    Lib.withBinning(
      column,
      findBinningStrategy(query, column, binningStrategyName),
    ),
    findTemporalBucket(query, column, temporalBucketName),
  );
}

type AggregationClauseOpts =
  | {
      operatorName: string;
      tableName?: never;
      columnName?: never;
    }
  | {
      operatorName: string;
      tableName: string;
      columnName: string;
    };

interface BreakoutClauseOpts {
  columnName: string;
  tableName?: string;
  temporalBucketName?: string;
  binningStrategyName?: string;
}

export interface ExpressionClauseOpts {
  name: string;
  operator: Lib.ExpressionOperator;
  args: (Lib.ExpressionArg | Lib.ExpressionClause)[];
  options?: Lib.ExpressionOptions | null;
}

interface OrderByClauseOpts {
  columnName: string;
  tableName: string;
  direction: Lib.OrderByDirection;
}

interface QueryWithClausesOpts {
  query?: Lib.Query;
  expressions?: ExpressionClauseOpts[];
  aggregations?: AggregationClauseOpts[];
  breakouts?: BreakoutClauseOpts[];
  orderBys?: OrderByClauseOpts[];
}

export function createQueryWithClauses({
  query = createQuery(),
  expressions = [],
  aggregations = [],
  breakouts = [],
  orderBys = [],
}: QueryWithClausesOpts) {
  const queryWithExpressions = expressions.reduce((query, expression) => {
    return Lib.expression(
      query,
      -1,
      expression.name,
      Lib.expressionClause(
        expression.operator,
        expression.args,
        expression.options,
      ),
    );
  }, query);

  const queryWithAggregations = aggregations.reduce((query, aggregation) => {
    return Lib.aggregate(
      query,
      -1,
      Lib.aggregationClause(
        findAggregationOperator(query, aggregation.operatorName),
        aggregation.columnName && aggregation.tableName
          ? columnFinder(query, Lib.visibleColumns(query, -1))(
              aggregation.tableName,
              aggregation.columnName,
            )
          : undefined,
      ),
    );
  }, queryWithExpressions);

  const queryWithBreakouts = breakouts.reduce((query, breakout) => {
    const breakoutColumn = columnFinder(
      query,
      Lib.breakoutableColumns(query, -1),
    )(breakout.tableName, breakout.columnName);
    return Lib.breakout(
      query,
      -1,
      withTemporalBucketAndBinningStrategy(
        query,
        breakoutColumn,
        breakout.temporalBucketName,
        breakout.binningStrategyName,
      ),
    );
  }, queryWithAggregations);

  return orderBys.reduce((query, orderBy) => {
    const orderByColumn = columnFinder(query, Lib.orderableColumns(query, -1))(
      orderBy.tableName,
      orderBy.columnName,
    );
    return Lib.orderBy(query, -1, orderByColumn, orderBy.direction);
  }, queryWithBreakouts);
}

export const queryDrillThru = (
  query: Lib.Query,
  stageIndex: number,
  clickObject: Lib.ClickObject,
  drillType: Lib.DrillThruType,
): Lib.DrillThru | null => {
  const drills = Lib.availableDrillThrus(
    query,
    stageIndex,
    undefined,
    clickObject.column,
    clickObject.value,
    clickObject.data,
    clickObject.dimensions,
  );
  const drill = drills.find((drill) => {
    const drillInfo = Lib.displayInfo(query, stageIndex, drill);
    return drillInfo.type === drillType;
  });

  return drill ?? null;
};

export const findDrillThru = (
  query: Lib.Query,
  stageIndex: number,
  clickObject: Lib.ClickObject,
  drillType: Lib.DrillThruType,
) => {
  const drill = queryDrillThru(query, stageIndex, clickObject, drillType);
  if (!drill) {
    throw new Error(`Could not find drill ${drillType}`);
  }

  const drillInfo = Lib.displayInfo(query, stageIndex, drill);
  return { drill, drillInfo };
};

interface ColumnClickObjectOpts {
  column: DatasetColumn;
}

export const getJoinQueryHelpers = (
  query: Lib.Query,
  stageIndex: number,
  tableId: TableId,
) => {
  const table = checkNotNull(Lib.tableOrCardMetadata(query, tableId));

  const findLHSColumn = columnFinder(
    query,
    Lib.joinConditionLHSColumns(query, stageIndex),
  );
  const findRHSColumn = columnFinder(
    query,
    Lib.joinConditionRHSColumns(query, stageIndex, table),
  );

  const defaultStrategy = Lib.availableJoinStrategies(query, stageIndex).find(
    (strategy) => Lib.displayInfo(query, stageIndex, strategy).default,
  );

  if (!defaultStrategy) {
    throw new Error("No default strategy found");
  }

  const defaultOperator = Lib.joinConditionOperators(query, stageIndex)[0];
  if (!defaultOperator) {
    throw new Error("No default operator found");
  }

  return {
    table,
    defaultStrategy,
    defaultOperator,
    findLHSColumn,
    findRHSColumn,
  };
};

export function createColumnClickObject({
  column,
}: ColumnClickObjectOpts): Lib.ClickObject {
  return { column };
}

interface RawCellClickObjectOpts {
  column: DatasetColumn;
  value: RowValue;
  data?: Lib.ClickObjectDataRow[];
}

export function createRawCellClickObject({
  column,
  value,
  data = [{ col: column, value }],
}: RawCellClickObjectOpts): Lib.ClickObject {
  return { column, value, data };
}

interface AggregatedCellClickObjectOpts {
  aggregation: Lib.ClickObjectDimension;
  breakouts: Lib.ClickObjectDimension[];
}

export function createAggregatedCellClickObject({
  aggregation,
  breakouts,
}: AggregatedCellClickObjectOpts): Lib.ClickObject {
  const data = [...breakouts, aggregation].map(({ column, value }) => ({
    key: column.name,
    col: column,
    value,
  }));

  return {
    column: aggregation.column,
    value: aggregation.value,
    data,
    dimensions: breakouts,
  };
}

interface PivotCellClickObjectOpts {
  aggregation: Lib.ClickObjectDimension;
  breakouts: Lib.ClickObjectDimension[];
}

export function createPivotCellClickObject({
  aggregation,
  breakouts,
}: PivotCellClickObjectOpts): Lib.ClickObject {
  const data = [...breakouts, aggregation].map(({ column, value }) => ({
    key: column.name,
    col: column,
    value,
  }));

  return { value: aggregation.value, data, dimensions: breakouts };
}

export function createLegendItemClickObject(
  dimension: Lib.ClickObjectDimension,
) {
  return { dimensions: [dimension] };
}
