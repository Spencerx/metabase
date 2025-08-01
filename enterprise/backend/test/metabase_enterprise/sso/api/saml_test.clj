(ns metabase-enterprise.sso.api.saml-test
  (:require
   [clojure.test :refer :all]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]))

(use-fixtures :once fixtures/initialize :db :test-users)

(deftest saml-settings-test
  (testing "PUT /api/saml/settings"
    (mt/with-premium-features #{}
      (testing "SAML settings cannot be saved without SAML feature flag enabled"
        (mt/assert-has-premium-feature-error "SAML-based authentication" (mt/user-http-request :crowberto :put 402 "saml/settings" {:saml-keystore-path "test_resources/keystore.jks"
                                                                                                                                    :saml-keystore-password "123456"
                                                                                                                                    :saml-keystore-alias "sp"}))))
    (mt/with-premium-features #{:sso-saml}
      (testing "Requires idp issuer to be non-nil if sent"
        (mt/user-http-request :crowberto :put 400 "saml/settings" {:saml-identity-provider-issuer nil}))
      (testing "Requires idp uri to be non-nil if sent"
        (mt/user-http-request :crowberto :put 400 "saml/settings" {:saml-identity-provider-uri nil}))
      (testing "Requires idp cert to be non-nil if sent"
        (mt/user-http-request :crowberto :put 400 "saml/settings" {:saml-identity-provider-certificate nil}))
      (testing "Valid SAML settings can be saved via an API call"
        (mt/user-http-request :crowberto :put 200 "saml/settings" {:saml-keystore-path "test_resources/keystore.jks"
                                                                   :saml-keystore-password "123456"
                                                                   :saml-keystore-alias "sp"}))
      (testing "Blank SAML settings returns 200"
        (mt/user-http-request :crowberto :put 200 "saml/settings" {:saml-keystore-path nil
                                                                   :saml-keystore-password nil
                                                                   :saml-keystore-alias nil}))

      (testing "Invalid SAML settings returns 400"
        (mt/user-http-request :crowberto :put 400 "saml/settings" {:saml-keystore-path "/path/to/keystore"
                                                                   :saml-keystore-password "password"
                                                                   :saml-keystore-alias "alias"})))))
