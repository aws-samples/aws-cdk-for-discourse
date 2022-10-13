#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import {DiscourseStack} from "../lib/discourse-stack";

const app = new cdk.App();

// export CDK_DEPLOY_DISCOURSE_ACCOUNT='123456789'
// export CDK_DEPLOY_DISCOURSE_REGION='us-east-1'
// export CDK_DEPLOY_DISCOURSE_STACK_ID='Discourse'
// export CDK_DEPLOY_DISCOURSE_HOSTED_ZONE_ID='Z0123456789'
// export CDK_DEPLOY_DISCOURSE_HOSTED_ZONE_NAME='example.com'
// export CDK_DEPLOY_DISCOURSE_DOMAIN_NAME='discourse.example.com'
// export CDK_DEPLOY_DISCOURSE_SES_SMTP_DOMAIN_NAME='discourse.example.com'
// export CDK_DEPLOY_DISCOURSE_NOTIFICATION_EMAIL='noreply@discourse.example.com'
// export CDK_DEPLOY_DISCOURSE_DEVELOPER_EMAILS='admin@example.com'
// export CDK_DEPLOY_DISCOURSE_COGNITO_AUTH_SUB_DOMAIN_NAME='discourse-{uniquename}'
// export CDK_DEPLOY_DISCOURSE_SETTINGS_SECRET_ARN='arn:aws:secretsmanager:{region}:{account}:secret:{secret}'
// export CDK_DEPLOY_DISCOURSE_CIDR='10.0.0.0/16'
// export CDK_DEPLOY_DISCOURSE_CLOUDFRONT_ALB_HEADER_CHECK_HEADER='X-Discourse-ALB-Check'
// export CDK_DEPLOY_DISCOURSE_CLOUDFRONT_ALB_HEADER_CHECK_VALUE='c9fd4d17-24a6-463f-b470-1c4347253245'

new DiscourseStack(app, process.env.CDK_DEPLOY_DISCOURSE_STACK_ID || 'Discourse', {
    env: {
        account: process.env.CDK_DEPLOY_DISCOURSE_ACCOUNT || process.env.CDK_DEFAULT_ACCOUNT,
        region: process.env.CDK_DEPLOY_DISCOURSE_REGION || process.env.CDK_DEFAULT_REGION
    }
});