package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

import java.io.IOException;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import software.constructs.Construct;

public class DiscourseTest {

    @Test
    public void testStack() throws IOException {
//        App app = new App();
//
//        DiscourseStack stack = new DiscourseStack(app, "DiscourseTest", "HostedZoneId", "HostedZoneName",
//                "cdksample.com", "discourse.cdksample.com", "noreply@discourse.cdksample.com",
//                "admin@cdksample.com", "cdksample", "10.0.0.0/16", "arn:full:arn:to:secret",
//        "CustomHeader", "CustomHeaderValue", StackProps.builder().env(Environment.builder()
//                .account("123456789")
//                .region("us-east-1")
//                .build()).build());
//
//        Template template = Template.fromStack(stack);
    }
}
