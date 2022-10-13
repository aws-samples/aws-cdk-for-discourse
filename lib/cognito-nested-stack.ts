import {Construct} from 'constructs';
import {Duration, NestedStack, NestedStackProps, RemovalPolicy} from "aws-cdk-lib";
import {
    AccountRecovery,
    ClientAttributes,
    Mfa,
    OAuthScope,
    UserPool,
    UserPoolClient,
    UserPoolClientIdentityProvider,
    UserPoolDomain,
    UserPoolEmail
} from "aws-cdk-lib/aws-cognito";

export class CognitoNestedStack extends NestedStack {
    userPool: UserPool;
    userPoolClient: UserPoolClient;

    constructor(scope: Construct, id: string, props?: NestedStackProps) {
        super(scope, id, props);

        this.userPool = new UserPool(this, id + "UserPool", {
            removalPolicy: RemovalPolicy.DESTROY,
            email: UserPoolEmail.withCognito(),
            signInCaseSensitive: false,
            signInAliases: {
                email: true,
                username: false,
                phone: false
            },
            mfa: Mfa.OPTIONAL,
            mfaSecondFactor: {
                otp: true,
                sms: true
            },
            accountRecovery: AccountRecovery.EMAIL_AND_PHONE_WITHOUT_MFA,
            autoVerify: {
                email: true
            },
            enableSmsRole: true,
            passwordPolicy: {
                minLength: 8,
                requireDigits: true,
                requireSymbols: true,
                requireUppercase: true,
                requireLowercase: true,
                tempPasswordValidity: Duration.days(7),
            },
            selfSignUpEnabled: true,
            standardAttributes: {
                email: {required: true, mutable: true},
                address: {mutable: true},
                birthdate: {mutable: true},
                familyName: {mutable: true},
                gender: {mutable: true},
                givenName: {mutable: true},
                locale: {mutable: true},
                middleName: {mutable: true},
                fullname: {mutable: true},
                nickname: {mutable: true},
                phoneNumber: {required: true, mutable: true},
                profilePicture: {mutable: true},
                preferredUsername: {mutable: true},
                profilePage: {mutable: true},
                timezone: {mutable: true},
                lastUpdateTime: {mutable: true},
                website: {mutable: true},
            }
        });

        new UserPoolDomain(this, id + "UserPoolDomain", {
            userPool: this.userPool,
            cognitoDomain: {
                domainPrefix: process.env.CDK_DEPLOY_DISCOURSE_COGNITO_AUTH_SUB_DOMAIN_NAME || ''
            }
        });

        const clientReadAttributes = (new ClientAttributes()).withStandardAttributes({
            address: true,
            birthdate: true,
            email: true,
            emailVerified: true,
            familyName: true,
            gender: true,
            givenName: true,
            locale: true,
            middleName: true,
            fullname: true,
            nickname: true,
            phoneNumber: true,
            phoneNumberVerified: true,
            profilePicture: true,
            preferredUsername: true,
            profilePage: true,
            timezone: true,
            lastUpdateTime: true,
            website: true
        });
        const clientWriteAttributes = (new ClientAttributes()).withStandardAttributes({
            address: true,
            birthdate: true,
            email: true,
            familyName: true,
            gender: true,
            givenName: true,
            locale: true,
            middleName: true,
            fullname: true,
            nickname: true,
            phoneNumber: true,
            profilePicture: true,
            preferredUsername: true,
            profilePage: true,
            timezone: true,
            lastUpdateTime: true,
            website: true
        });
        this.userPoolClient = new UserPoolClient(this, id + "UserPoolApp", {
            supportedIdentityProviders: [
                UserPoolClientIdentityProvider.COGNITO
            ],
            oAuth: {
                callbackUrls: [
                    'https://' + (process.env.CDK_DEPLOY_DISCOURSE_DOMAIN_NAME || '') + '/auth/oidc/callback'
                ],
                flows: {
                    authorizationCodeGrant: true
                },
                scopes: [
                    OAuthScope.PHONE,
                    OAuthScope.EMAIL,
                    OAuthScope.OPENID,
                    OAuthScope.COGNITO_ADMIN,
                    OAuthScope.PROFILE
                ]
            },
            refreshTokenValidity: Duration.days(30),
            idTokenValidity: Duration.minutes(60),
            enableTokenRevocation: true,
            generateSecret: true,
            preventUserExistenceErrors: true,
            userPool: this.userPool,
            readAttributes: clientReadAttributes,
            writeAttributes: clientWriteAttributes,
            authFlows: {
                adminUserPassword: false,
                userPassword: false,
                userSrp: false,
                custom: false
            }
        });
    }
}
