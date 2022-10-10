package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

import java.util.InvalidPropertiesFormatException;

public class DiscourseApp {
    static String getVariableOrDefault(String variableName, String defaultValue) {
        String value = System.getenv(variableName);
        value = (value == null) ? defaultValue : value;
        return value;
    }

    public static void main(final String[] args) throws InvalidPropertiesFormatException {
        App app = new App();

        new DiscourseStack(app,
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_STACK_ID", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_HOSTED_ZONE_ID", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_HOSTED_ZONE_NAME", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_DOMAIN_NAME", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_SES_SMTP_DOMAIN_NAME", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_NOTIFICATION_EMAIL", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_DEVELOPER_EMAILS", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_COGNITO_AUTH_SUB_DOMAIN_NAME", ""),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_CIDR", "10.0.0.0/16"),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_SETTINGS_SECRET_ARN", null),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_CLOUDFRONT_ALB_HEADER_CHECK_HEADER", "X-Discourse-ALB-Check"),
                getVariableOrDefault("CDK_DEPLOY_DISCOURSE_CLOUDFRONT_ALB_HEADER_CHECK_VALUE", "c9fd4d17-24a6-463f-b470-1c4347253245"),
                StackProps.builder().env(Environment.builder()
                    .account(getVariableOrDefault("CDK_DEPLOY_DISCOURSE_ACCOUNT", System.getenv("CDK_DEFAULT_ACCOUNT")))
                    .region(getVariableOrDefault("CDK_DEPLOY_DISCOURSE_REGION", System.getenv("CDK_DEFAULT_REGION")))
                    .build()).build());
        app.synth();
    }
}

