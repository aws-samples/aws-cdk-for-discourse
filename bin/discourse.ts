#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import {DiscourseStack} from "../lib/discourse-stack";

const app = new cdk.App();

new DiscourseStack(app, process.env.CDK_DEPLOY_DISCOURSE_STACK_ID || 'Discourse', {
    env: {
        account: process.env.CDK_DEPLOY_DISCOURSE_ACCOUNT || process.env.CDK_DEFAULT_ACCOUNT,
        region: process.env.CDK_DEPLOY_DISCOURSE_REGION || process.env.CDK_DEFAULT_REGION
    }
});