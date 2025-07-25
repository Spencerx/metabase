const { H } = cy;

import { setupSaml } from "./sso/shared/helpers.js";

describe("scenarios > admin > settings > user provisioning", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
  });

  describe("oss", { tags: "@OSS" }, () => {
    it("user provisioning page should not be available for OSS customers", () => {
      cy.visit("/admin/settings/authentication/user-provisioning");

      // falls back to the authentication page
      cy.findByTestId("google-setting").should("be.visible");
      cy.findByTestId("ldap-setting").should("be.visible");
      cy.findByTestId("api-keys-setting").should("be.visible");

      // no EE auth providers
      cy.findByTestId("saml-setting").should("not.exist");
    });
  });

  describe("scim settings management", () => {
    beforeEach(() => {
      H.activateToken("pro-self-hosted");
    });

    it("should be able to setup and manage scim feature", () => {
      cy.visit("/admin/settings/authentication/user-provisioning");

      cy.log(
        "should not show endpoint and token inputs if scim has never been enabled before",
      );
      H.main().within(() => {
        scimEndpointInput().should("not.exist");
        scimTokenInput().should("not.exist");
      });

      cy.log("can enable scim");
      scimToggle().should("exist");
      scimToggle().click();

      let initialUnmaskedToken = "";
      cy.log("should show unmasked info in modal");
      H.modal().within(() => {
        cy.findByText("Here's what you'll need to set SCIM up").should("exist");
        scimEndpointInput().invoke("val").should("contain", "/api/ee/scim/v2");
        scimTokenInput()
          .invoke("val")
          .should("not.contain", "****************************************");
        // save to compare with masked token
        scimTokenInput()
          .invoke("val")
          .then((val) => (initialUnmaskedToken = String(val)));
        cy.findAllByRole("button", { name: /Done/ }).click();
      });

      cy.log(
        "should show masked info in main page + should match unmasked token",
      );
      scimEndpointInput()
        .should("exist")
        .invoke("val")
        .should("contain", "/api/ee/scim/v2");

      scimTokenInput()
        .invoke("val")
        .should("contain", "mb_")
        .should("contain", "****************************************")
        .then((val) =>
          expect(val).to.contain(initialUnmaskedToken.slice(0, 7)),
        );

      cy.log("should be able to regenerate a token");
      cy.findByRole("button", { name: /Regenerate/ }).click();

      H.modal().within(() => {
        cy.findByText("Regenerate token?").should("exist");
        cy.findByRole("button", { name: /Regenerate now/ }).click();
      });

      let regeneratedToken = "";
      H.modal().within(() => {
        cy.findByText("Copy and save the SCIM token").should("exist");
        scimTokenInput()
          .invoke("val")
          .should("not.contain", "Loading")
          .should("not.contain", "****************************************")
          .then((val) => (regeneratedToken = String(val)));
        cy.findByRole("button", { name: /Done/ }).click();
      });

      scimTokenInput()
        .invoke("val")
        .should("contain", "mb_")
        .should("contain", "****************************************")
        .then((val) => expect(val).to.contain(regeneratedToken.slice(0, 7)));

      cy.log("should be able to cancel regenerating a token");
      cy.findByRole("button", { name: /Regenerate/ }).click();

      H.modal().within(() => {
        cy.findByText("Regenerate token?").should("exist");
        cy.findByRole("button", { name: /Cancel/ }).click();
      });
      H.modal().should("not.exist");

      scimTokenInput()
        .invoke("val")
        .should("contain", "mb_")
        .should("contain", "****************************************")
        .then((val) => expect(val).to.contain(regeneratedToken.slice(0, 7)));

      cy.log("should be able to disable scim and info stay");
      scimToggle().click();
      scimToggle().findByText("Disabled");
      scimEndpointInput().should("be.visible");
      scimTokenInput().should("be.visible");
      cy.findByRole("button", { name: /Regenerate/ }).should("be.disabled");

      cy.log("should be able to re-enable");
      scimToggle().click();
      scimToggle().findByText("Enabled");
    });

    it("should warn users that saml user provisioning will be disabled before enabling scim", () => {
      setupSaml();
      cy.visit("/admin/settings/authentication/user-provisioning");

      const samlWarningMessage =
        "When enabled, SAML user provisioning will be turned off in favor of SCIM.";

      H.main().within(() => {
        cy.log("message should exist while scim has never been enabled");
        cy.findByText(samlWarningMessage).should("exist");

        cy.log("message should not exist once scim has been enabled");
        scimToggle().findByText("Disabled");
        scimToggle().click();
        scimToggle().findByText("Enabled");
      });

      H.modal().within(() => {
        cy.findByRole("button", { name: /Done/ }).click();
      });

      H.main().within(() => {
        cy.findByText(samlWarningMessage).should("not.exist");

        cy.log(
          "message should still not exist even after scim has been disabled",
        );
        scimToggle().click();
        scimToggle().should("not.be.checked");
        cy.findByText(samlWarningMessage).should("not.exist");
      });
    });

    it("should properly handle errors", () => {
      cy.log("should show error when scim token fails to load");
      cy.intercept("GET", "/api/ee/scim/api_key", { statusCode: 500 });
      cy.visit("/admin/settings/authentication/user-provisioning");
      H.main().within(() => {
        cy.findByText("Error fetching SCIM token");
      });

      cy.log(
        "should show error when scim token fails to generate when scim is enabled",
      );
      // enable scim and stop mocking get scim api key request
      cy.intercept("GET", "/api/ee/scim/api_key", (req) => {
        req.continue();
      });
      cy.request("PUT", "api/setting/scim-enabled", { value: true });
      cy.visit("/admin/settings/authentication/user-provisioning");
      H.main().within(() => {
        cy.findByText("Token failed to generate, please regenerate one.");
      });

      cy.log("should show error when scim token fails to regenerate");
      cy.intercept("POST", "/api/ee/scim/api_key", {
        statusCode: 500,
        body: { message: "An error occurred" },
      });
      cy.findByRole("button", { name: /Regenerate/ }).click();

      H.modal().within(() => {
        cy.findByText("Regenerate token?").should("exist");
        cy.findByRole("button", { name: /Regenerate now/ }).click();
      });

      H.modal().within(() => {
        cy.findByText("An error occurred");
      });
    });
  });
});

function scimToggle() {
  return cy.findByTestId("scim-enabled-setting").findByText(/Enabled|Disabled/);
}

function scimEndpointInput() {
  return cy.findByLabelText("SCIM endpoint URL");
}

function scimTokenInput() {
  return cy.findByLabelText("SCIM token");
}
