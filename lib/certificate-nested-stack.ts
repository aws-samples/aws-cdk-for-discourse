import * as process from "process";
import {Construct} from 'constructs';
import {NestedStack, NestedStackProps} from "aws-cdk-lib";
import {IPublicHostedZone} from "aws-cdk-lib/aws-route53";
import {CertificateValidation, DnsValidatedCertificate} from "aws-cdk-lib/aws-certificatemanager";

interface CertificateNestedStackProps extends NestedStackProps {
    readonly hostedZone: IPublicHostedZone;
}

export class CertificateNestedStack extends NestedStack {
    certificate: DnsValidatedCertificate;

    constructor(scope: Construct, id: string, props: CertificateNestedStackProps) {
        super(scope, id, props);
        this.certificate = new DnsValidatedCertificate(this, id + "Certificate", {
            hostedZone: props.hostedZone,
            domainName: process.env.CDK_DEPLOY_DISCOURSE_DOMAIN_NAME || '',
            cleanupRoute53Records: true,
            region: this.region,
            validation: CertificateValidation.fromDns(props.hostedZone)
        });
    }
}
