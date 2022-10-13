import {Construct} from 'constructs';
import {NestedStack, NestedStackProps, SecretValue} from "aws-cdk-lib";
import {Secret} from "aws-cdk-lib/aws-secretsmanager";
import {IPublicHostedZone} from "aws-cdk-lib/aws-route53";
import {EmailIdentity, Identity} from "aws-cdk-lib/aws-ses";
import {AccessKey, AccessKeyStatus, Effect, Policy, PolicyStatement, User} from "aws-cdk-lib/aws-iam";

interface VerifiedSeSDomainNestedStackProps extends NestedStackProps {
    readonly hostedZone: IPublicHostedZone;
}

export class VerifiedSeSDomainNestedStack extends NestedStack {
    secret: Secret;
    smtpUserAccessKeyId: string;

    constructor(scope: Construct, id: string, props: VerifiedSeSDomainNestedStackProps) {
        super(scope, id, props);
        const emailIdentity = new EmailIdentity(this, id + "EmailIdentity", {
            identity: Identity.domain(process.env.CDK_DEPLOY_DISCOURSE_SES_SMTP_DOMAIN_NAME || '')
        });
        const smtpUser = new User(this, id + "SMTPUser", {
            passwordResetRequired: false
        });
        smtpUser.attachInlinePolicy(new Policy(this, id + "SMTPSendRawEmailPolicy", {
            statements: [new PolicyStatement({
                resources: ['*'],
                actions: ['ses:SendRawEmail'],
                effect: Effect.ALLOW
            })
            ]
        }));
        const accessKey = new AccessKey(this, id + "SMTPAccessKey", {
            user: smtpUser,
            status: AccessKeyStatus.ACTIVE
        });
        this.smtpUserAccessKeyId = accessKey.accessKeyId;
        this.secret = new Secret(this, id + "SMTPUserSecretAccessKeySecret", {
            secretObjectValue: {
                'SecretAccessKey': accessKey.secretAccessKey
            }
        });
    }
}
