---
title: End-to-end tests with Cypress
---

# End-to-end tests with Cypress

Metabase uses Cypress for “end-to-end testing”, that is, tests that are executed against the application as a whole, including the frontend, backend, and application database. These tests are essentially scripts written in JavaScript that run in the web browser: visit different URLs, click various UI elements, type text, and assert that things happen as expected (for example, an element appearing on screen, or a network request occuring).

_Please, get familiar with the [Cypress best practices](https://docs.cypress.io/app/core-concepts/best-practices) before you proceed._

## Getting Started

Metabase’s Cypress tests are located in the `e2e/test/scenarios` source tree, in a structure that roughly mirrors Metabase’s URL structure. For example, tests for the admin “datamodel” pages are located in `e2e/test/scenarios/admin/datamodel`.

Our custom Cypress runner builds its own backend and creates a temporary H2 app db. Both are destroyed when this process is killed. The reserved default port is `4000` on the local host. There is nothing stopping you from running your local Metabase instance on `localhost:3000` at the same time. This might even be helpful for debugging purposes.

### Standard Development Flow

1. Continuously build the frontend

   a. If you need only the frontend, run `yarn build-hot`

   b. If you want to run a local Metabase instance alongside Cypress, the easiest way to achieve this is by using `yarn dev` or `yarn dev-ee` (both rely on frontend hot reloading under the hood)

2. In a separate terminal session (without killing the previous one) run `yarn test-cypress`. This will open a Cypress GUI that will let you choose which tests to run. Alternatively, take a look at `run_cypress_local.js` and `e2e/test/scenarios/docker-compose.yml` for all possible options.

### Running Options

To run all Cypress tests headlessly in the terminal:

```sh
OPEN_UI=false yarn run test-cypress
```

You can quickly test a single file only by using the official `--spec` flag.
This flag can be used to run all specs within a folder, or to run multiple assorted specs. Consult [the official documentation](https://docs.cypress.io/app/references/command-line#cypress-run-spec-lt-spec-gt) for instructions.

```sh
OPEN_UI=false yarn test-cypress --spec e2e/test/scenarios/question/new.cy.spec.js
```

You can specify a browser to execute Cypress tests in using the `--browser` flag. For more details, please consult [the official documentation](https://docs.cypress.io/guides/guides/launching-browsers).

Specifying a browser makes most sense when running Cypress in a _run_ mode. On the other hand, Cypress _open_ mode (GUI) allows one to easily switch between all available browsers on the system. However, some people prefer to specify a browser even in this scenario. If you do this, keep in mind that you are merely preselecting an initial browser for Cypress but you still have the option to choose a different one.

## Anatomy of the Test

Cypress test files are structured like Mocha tests, where `describe` blocks are used to group related tests, and `it` blocks are the tests themselves.

```js
describe("homepage", () => {
  it("should load the homepage and...", () => {
    cy.visit("/metabase/url");
    // ...
  });
});
```

We strongly prefer using selectors like `cy.findByText()` and `cy.findByLabelText()` from [`@testing-library/cypress`](https://github.com/testing-library/cypress-testing-library) since they encourage writing tests that don't depend on implementation details like CSS class names.

Try to avoid repeatedly testing pieces of the application incidentally. For example, if you want to test something about the query builder, jump straight there using a helper like `openOrdersTable()` rather than starting from the home page, clicking "New", then "Question", etc.

## Cypress Documentation

- Introduction: https://docs.cypress.io/guides/core-concepts/introduction-to-cypress.html
- Commands: https://docs.cypress.io/api/api/table-of-contents.html
- Assertions: https://docs.cypress.io/guides/references/assertions.html

## Tips/Gotchas

### `contains` vs `find` vs `get`

Cypress has a set of similar commands for selecting elements. Here are some tips for using them:

- [`contains`](https://docs.cypress.io/api/commands/contains) is (by default) case-sensitive to the text _in the DOM_. If it’s not matching text you’d expect, check that CSS hasn’t updated the case. You can explicitly tell it to ignore the case with the following option `{ matchCase: false }`.
  - `contains` matches substrings. Given two strings “filter by” and “Add a filter”, `cy.contains(“filter”);` will match both. To avoid these issues, you can either pass a regexp that pins the start/end of the string or scope a string to a specific selector: `cy.contains(selector, content);`.
- [`find`](https://docs.cypress.io/api/commands/find) will let you search within your previous selection.
- [`get`](https://docs.cypress.io/api/commands/get) will search the entire page even if chained, unless you explicitly tweak the `withinSubject` option.

### How to access Sample Database tables and field IDs?

The Sample Database that we use in E2E tests can change at any time, and with it the references to its tables and fields. Never **ever** use hard coded numeric references to those IDs. We provide a helpful mechanism to achieve this that is guaranteed to produce correct results. Every time you spin Cypress up, it fetches the information about the Sample Database, extracts table and field IDs and writes that to the `e2e/support/cypress_sample_database` JSON that we then re-export and make available to all tests.

```js
// Don't
const query = {
  "source-table": 1,
  aggregation: [["count"]],
  breakout: [["field", 7, null]],
};

// Do this instead
import { SAMPLE_DATABASE } from "e2e/support/cypress_sample_database";
const { PRODUCTS, PRODUCTS_ID } = SAMPLE_DATABASE;

const query = {
  "source-table": PRODUCTS_ID,
  aggregation: [["count"]],
  breakout: [["field", PRODUCTS.CATEGORY, null]],
};
```

### Increase viewport size to avoid scrolling

Sometimes Metabase views are a bit large for Cypress’ default 1280x800 viewport. This can require you to scroll for tests to work. For example, virtualized tables will not even render the contents outside of the viewport. To avoid these problems, increase the viewport size for a specific test. Unless you're specifically testing how the application behaves on a window resize, please avoid using the `cy.viewport(width, height);` in the middle of the test. Set the viewport width/height using the optional Cypress test config instead. This config works with both `describe` and `it` blocks.

```js
describe("foo", { viewportWidth: 1400 }, () => {});

it("bar", { viewportWidth: 1600, viewportHeight: 1200 }, () => {});
```

### Code reloading vs test reloading

When you edit a Cypress test file, the tests will refresh and run again. However, when you edit a code file, Cypress won’t detect that change. If you’re running `yarn build-hot`, the code will rebuild and update within Cypress. You’ll have to manually click rerun after the new code has loaded.

### Inspecting while the “contains helper” is open

One great feature of Cypress is that you can use the Chrome inspector after each step of a test. They also helpfully provide a helper that can test out `contains` and `get` calls. This helper creates new UI that prevents inspecting from targeting the correct elements. If you want to inspect the DOM in Chrome, you should close this helper.

### Putting the wrong HTML template in the Uberjar

`yarn build` and `yarn build-hot` each overwrite an HTML template to reference the correct JavaScript files. If you run `yarn build` before building an Uberjar for Cypress tests, you won’t see changes to your JavaScript reflected even if you then start `yarn build-hot`.

### Running Cypress on M1 machines

You might run into problems when running Cypress on M1 machine.
This is caused by the `@bahmutov/cypress-esbuild-preprocessor` that is using `esbuild` as a dependency. The error might look [like this](https://github.com/evanw/esbuild/issues/1819#issuecomment-1018771557). [The solution](https://github.com/evanw/esbuild/issues/1819#issuecomment-1080720203) is to install NodeJS using one of the Node version managers like [nvm](https://github.com/nvm-sh/nvm) or [n](https://github.com/tj/n).

Another issue you will almost surely face is the inability to connect to our Mongo QA Database. You can solve it by providing the following env:

```shell
export EXPERIMENTAL_DOCKER_DESKTOP_FORCE_QEMU=1
```

### Running tests that depend on Docker images

A subset of our tests depend on the external services that are available through the Docker images. At the time of this writing, those are the three supported external QA databases, Webmail, Snowplow and LDAP servers. The default cypress command will spin up all necessary docker containers for these tests to function properly, but you can toggle them off if you want

```sh
START_CONTAINERS=false yarn test-cypress
```

### Running tests with Snowplow involved

Tests that depend on Snowplow expect a running server. This is enabled by default. You can manually enable them as well by spinning up the snowplow micro docker container and setting the appropriate environment variables:

```
docker-compose -f ./snowplow/docker-compose.yml up -d
export MB_SNOWPLOW_AVAILABLE=true
export MB_SNOWPLOW_URL=http://localhost:9090
```

## Testing with Snowplow

We have a few helpers for dealing with tests involving snowplow

1. You can use `describeWithSnowplow` (or `describeWithSnowplowEE` for EE edition) method to define tests that only
   run when a Snowplow instance is running
1. Use `resetSnowplow()` test helper before each test to clear the queue of processed events.
1. Use `expectSnowplowEvent({ ...payload }, count=n)` to assert that exactly `count` snowplow events match (partially)
   the payload provided (count defaults to 1)
1. Use `expectUnstructuredSnowplowEvent` to assert that exactly `count` snowplow events are unstructured events that
   partial-match the payload provided. This is simply a convenience function for comparing
   `event.unstruct_event.data.data` rather than the entire `event`. Most of our events are unstructured events, so this is handy.
1. Use `assertNoUnstructuredSnowplowEvent({ ...eventData })` is the inverse of `expectUnstructuredSnowplowEvent`, and asserts that
   _no_ unstructured events match the payload.
1. Use `expectNoBadSnowplowEvents()` after each test to assert that no invalid events have been sent.

### Running tests that require SMTP server

Some of our tests depend on the email being set up, and require a local SMTP server. We use `maildev` Docker image for that purpose. At the time of this writing the image we use is `maildev/maildev:2.1.0`. The default cypress configuration for local development will handle this for you. If you want to set it up manually, you can use this command:

```sh
docker run -d -p 1080:1080 -p 1025:1025 maildev/maildev:latest
```

### Cypress comes with `Lodash` for free

We don't need to have [Lodash](https://lodash.com/) in our direct dependencies to be able to [use it with Cypress](https://docs.cypress.io/api/utilities/_). It is aliased with an underscore and its methods can be accessed with `Cypress._.method()`. We can use `_.times` method to stress-test a certain test (or a set of tests) locally.

```js
// Run the test N times
Cypress._.times(N, () => {
  it("should foo", () => {
    // ...
  });
});
```

### Embedding SDK tests

See [sdk docs about e2e](https://github.com/metabase/metabase/blob/master/enterprise/frontend/src/embedding-sdk/dev.md)

## DB Snapshots

At the beginning of each test suite we wipe the backend's db and settings cache. This ensures that the test suite starts in a predictable state.

Typically, we use the default snapshot by adding `before(restore)` inside the first `describe` block to restore before running the whole test suite. If you want to use a snapshot besides the default one, specify the name as an argument to `restore` like this: `before(() => restore("blank"))`. You can also call `restore()` inside `beforeEach()` to reset before every test, or inside specific tests.

Snapshots are created with a separate set of Cypress tests. These tests start with a blank database and execute specific actions to put the database in predictable state. For example: signup as bob@metabase.com, add a question, turn on setting ABC.

These snapshot-generating tests have the extension `.cy.snap.js`. When these tests run they create db dumps in `frontend/tests/snapshots/*.sql`. They are run before the tests begin and don't get committed to git.

## Running in CI

Cypress records videos of each test run, which can be helpful in debugging. Additionally, failed tests have higher quality images saved.

These files can be found under the “Artifacts” section for each run's summary in GitHub Actions.
The example of the artifacts for a failed test in "Onboarding" directory:
![GitHub Actions artifacts section](https://user-images.githubusercontent.com/31325167/241774190-f19da1d5-8fca-4c48-9342-ead18066bd12.png)

## Running Cypress tests against Metabase® Enterprise Edition™

Prior to running Cypress against Metabase® Enterprise Edition™, set `MB_EDITION=ee` environment variable.

**Enterprise instance will start without a premium token!**

If you want to test premium features (feature flags), valid tokens need to be available to all Cypress tests.
You should provide 4 tokens:

- MB_ALL_FEATURES_TOKEN: all feature enabled, including new feature not released yet to customers
- MB_STARTER_CLOUD_TOKEN: only 'hosting' feature enabled to simulate the starter plan on cloud
- MB_PRO_CLOUD_TOKEN: PRO features enabled + 'hosting' to simulate the pro plan on cloud
- MB_PRO_SELF_HOSTED_TOKEN: PRO features but no 'hosting' to simulate the pro self-hosted plan

You can configure these via ENVs or via the `cypress.env.json` file (see `cypress.env.json.example` for an example).

For more information, please see [Metabase pricing page](https://www.metabase.com/pricing/).

If you navigate to the `/admin/settings/license` page, the license input field should display the active token. Be careful when sharing screenshots!

- If tests start running but the enterprise features are missing: make sure that the token you use has corresponding feature flags enabled.
- If everything with the token seems to be okay, go nuclear and destroy all Java processes: run `killall java` and restart Cypress.

## Tags

Cypress allows us to [tag](https://github.com/cypress-io/cypress/tree/develop/npm/grep#tags-in-the-test-config-object) tests, to easily find certain categories of tags. For example, we can tag all tests that require an external database with `@external` and then run only those tests with `yarn test-cypress --env grepTags="@external"`. Tags should start with `@` just to make it easier to distinguish them from other strings in searches.

These are the tags currently in use:

- `@external` - tests that require an external docker container to run
- `@actions` - tests that use metabase actions and mutate data in a data source

## How to stress-test a flake fix?

Fixing a flaky test locally doesn't mean the fix works in GitHub's CI environment. The only way to be sure the fix works is to stress-test it in CI. That's what `.github/workflows/e2e-stress-test-flake-fix.yml` is made for. It allows you to quickly test the fix in your branch without waiting for the full build to complete.

Please follow these steps:

### Prepare

- Create a new branch with your proposed fix and push it to the remote
- Either skip opening a PR altogether or open a **draft** pull request

### Trigger the stress-test workflow manually

- Go to `https://github.com/metabase/metabase/actions/workflows/e2e-stress-test-flake-fix.yml`
- Click on _Run workflow_ trigger next to "This workflow has a workflow_dispatch event trigger."

1. Choose your own branch in the first field "Use workflow from" (this part is crucial!)
2. Copy and paste the relative path of the spec you want to test (e.g. `e2e/test/scenarios/onboarding/urls.cy.spec.js`) - you don't have to wrap it in quotes
3. Set the desired number of times to run the test
4. Optionally provide a grep filter, according to [the documentation](https://github.com/cypress-io/cypress/tree/develop/npm/grep)
5. Click the green "Run workflow" button and wait for the results

### Things to keep in mind when using this workflow

- It will automatically try to find and download the previously built Metabase uberjar stored as an artifact from one of the past commits / CI runs.
- It was intended to be used for pure E2E fixes that don't require new Metabase uberjar.
- If the fix required a source-code change (either backend of frontend), please open a regular PR instead and let the CI run all tests first. After this,
  you can trigger the stress-test workflow manually, as explained above, and it will automatically download newly built artifact from this CI run. Please,
  keep in mind that CI needs to fully finish running first. The workflow uses GitHub REST API which doesn't see artifacts otherwise.

## Reports

Individual Mocha reports are automatically generated per spec. They are stored in `cypress/reports/mochareports`. Please keep in mind that the root level `cypress/` folder is git ignored!

When tests run _in CI_ we do some extra steps by merging these individual reports (using `mochawesome-merge`), formatting them, and then generating customized GitHub Actions job summaries.

On an off chance that you need a unified test report _while running tests locally_, you can achieve that by invoking `yarn generate-cypress-html-report`.
