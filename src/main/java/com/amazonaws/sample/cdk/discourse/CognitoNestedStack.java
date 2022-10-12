package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.cognito.*;
import software.constructs.Construct;

import java.util.List;

public class CognitoNestedStack extends NestedStack {

    private UserPool userPool;
    private UserPoolClient userPoolClient;

    public CognitoNestedStack(final Construct scope, final String id, final String domainName, final String cognitoAuthSubDomainName) {
        super(scope, id);
        createCognitoUserPool(id);
        createCognitoUserPoolDomain(cognitoAuthSubDomainName, id);
        createCognitoUserPoolClient(domainName, id);
    }

    public UserPool getUserPool() {
        return userPool;
    }

    public UserPoolClient getUserPoolClient() {
        return userPoolClient;
    }

    private void createCognitoUserPool(final String id) {
        userPool = UserPool.Builder.create(this, id + "UserPool")
                .removalPolicy(RemovalPolicy.DESTROY)
                .email(UserPoolEmail.withCognito())
                .signInCaseSensitive(false)
                .signInAliases(SignInAliases.builder().email(true).phone(false).username(false).build())
                .mfa(Mfa.OPTIONAL)
                .mfaSecondFactor(MfaSecondFactor.builder().otp(true).sms(true).build())
                .accountRecovery(AccountRecovery.EMAIL_AND_PHONE_WITHOUT_MFA)
                .autoVerify(AutoVerifiedAttrs.builder().email(true).build())
                .enableSmsRole(true)
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(8)
                        .requireDigits(true)
                        .requireSymbols(true)
                        .requireUppercase(true)
                        .requireLowercase(true)
                        .tempPasswordValidity(Duration.days(7))
                        .build())
                .selfSignUpEnabled(true)
                .standardAttributes(
                        StandardAttributes.builder()
                                .email(StandardAttribute.builder()
                                        .required(true)
                                        .mutable(true)
                                        .build())
                                .address(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .birthdate(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .familyName(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .gender(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .givenName(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .locale(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .middleName(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .fullname(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .nickname(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .phoneNumber(StandardAttribute.builder()
                                        .required(true)
                                        .mutable(true)
                                        .build())
                                .profilePicture(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .preferredUsername(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .profilePage(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .timezone(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .lastUpdateTime(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .website(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .build())
                .build();
    }

    private void createCognitoUserPoolDomain(final String cognitoAuthSubDomainName, final String id) {
        UserPoolDomain.Builder.create(this, id + "UserPoolDomain")
                .userPool(userPool)
                .cognitoDomain(CognitoDomainOptions.builder()
                        .domainPrefix(cognitoAuthSubDomainName)
                        .build())
                .build();
    }

    private void createCognitoUserPoolClient(final String callbackDomain, final String id) {
        ClientAttributes clientReadAttributes = new ClientAttributes().withStandardAttributes(StandardAttributesMask.builder()
                .address(true)
                .birthdate(true)
                .email(true)
                .emailVerified(true)
                .familyName(true)
                .gender(true)
                .givenName(true)
                .locale(true)
                .middleName(true)
                .fullname(true)
                .nickname(true)
                .phoneNumber(true)
                .phoneNumberVerified(true)
                .profilePicture(true)
                .preferredUsername(true)
                .profilePage(true)
                .timezone(true)
                .lastUpdateTime(true)
                .website(true)
                .build());
        ClientAttributes clientWriteAttributes = new ClientAttributes().withStandardAttributes(StandardAttributesMask.builder()
                .address(true)
                .birthdate(true)
                .email(true)
                .familyName(true)
                .gender(true)
                .givenName(true)
                .locale(true)
                .middleName(true)
                .fullname(true)
                .nickname(true)
                .phoneNumber(true)
                .profilePicture(true)
                .preferredUsername(true)
                .profilePage(true)
                .timezone(true)
                .lastUpdateTime(true)
                .website(true)
                .build());
        userPoolClient = UserPoolClient.Builder.create(this, id + "UserPoolApp")
                .supportedIdentityProviders(List.of(
                        UserPoolClientIdentityProvider.COGNITO)
                )
                .oAuth(OAuthSettings.builder()
                        .callbackUrls(List.of("https://" + callbackDomain + "/auth/oidc/callback"))
                        .flows(OAuthFlows.builder()
                                .authorizationCodeGrant(true)
                                .build())
                        .scopes(List.of(OAuthScope.PHONE,
                                OAuthScope.EMAIL,
                                OAuthScope.OPENID,
                                OAuthScope.COGNITO_ADMIN,
                                OAuthScope.PROFILE))
                        .build())
                .refreshTokenValidity(Duration.days(30))
                .idTokenValidity(Duration.minutes(60))
                .enableTokenRevocation(true)
                .generateSecret(true)
                .preventUserExistenceErrors(true)
                .userPool(userPool)
                .readAttributes(clientReadAttributes)
                .writeAttributes(clientWriteAttributes)
                .authFlows(AuthFlow.builder()
                        .adminUserPassword(false)
                        .userPassword(false)
                        .userSrp(false)
                        .custom(false)
                        .build())
                .build();
    }
}
