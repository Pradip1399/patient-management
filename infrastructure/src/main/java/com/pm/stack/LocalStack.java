package com.pm.stack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.logs.*;
import software.amazon.awscdk.services.rds.*;

public class LocalStack extends Stack {

    private final Vpc vpc;
    private final Cluster ecsCluster;

    public LocalStack(final App scope, final String id, final StackProps props) {
        super(scope, id, props);

        // -------------------------
        // Infrastructure
        // -------------------------
        this.vpc = createVpc();
        this.ecsCluster = createEcsCluster();

        DatabaseInstance authServiceDb =
                createDatabase("AuthServiceDB", "auth-service-db");

        DatabaseInstance patientServiceDb =
                createDatabase("PatientServiceDB", "patient-service-db");

        // -------------------------
        // MSK IS HARD DISABLED
        // -------------------------
        // LocalStack does NOT support MSK.
        // Do NOT create AWS::MSK::Cluster.
        // This guarantees MSK never appears in cdk.out.
        Object mskCluster = null;

        // -------------------------
        // ECS Services
        // -------------------------
        FargateService authService =
                createFargateService(
                        "AuthService",
                        "auth-service",
                        List.of(4005),
                        authServiceDb,
                        Map.of("JWT_SECRET",
                                "Y2hhVEc3aHJnb0hYTzMyZ2ZqVkpiZ1RkZG93YWxrUkM="));

        authService.getNode().addDependency(authServiceDb);

        FargateService billingService =
                createFargateService(
                        "BillingService",
                        "billing-service",
                        List.of(4001, 9001),
                        null,
                        null);

        FargateService analyticsService =
                createFargateService(
                        "AnalyticsService",
                        "analytics-service",
                        List.of(4002),
                        null,
                        null);

        // NO MSK dependency added

        FargateService patientService =
                createFargateService(
                        "PatientService",
                        "patient-service",
                        List.of(4000),
                        patientServiceDb,
                        Map.of(
                                "BILLING_SERVICE_ADDRESS", "host.docker.internal",
                                "BILLING_SERVICE_GRPC_PORT", "9001"));

        patientService.getNode().addDependency(patientServiceDb);
        patientService.getNode().addDependency(billingService);

        createApiGatewayService();
    }

    // ------------------------------------------------------------------
    // VPC
    // ------------------------------------------------------------------
    private Vpc createVpc() {
        return Vpc.Builder.create(this, "PatientManagementVPC")
                .vpcName("PatientManagementVPC")
                .maxAzs(2)
                .build();
    }

    // ------------------------------------------------------------------
    // RDS
    // ------------------------------------------------------------------
    private DatabaseInstance createDatabase(String id, String dbName) {
        return DatabaseInstance.Builder.create(this, id)
                .engine(DatabaseInstanceEngine.postgres(
                        PostgresInstanceEngineProps.builder()
                                .version(PostgresEngineVersion.VER_15_4)
                                .build()))
                .vpc(vpc)
                .instanceType(
                        InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .allocatedStorage(20)
                .credentials(Credentials.fromGeneratedSecret("admin_user"))
                .databaseName(dbName)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    // ------------------------------------------------------------------
    // ECS Cluster
    // ------------------------------------------------------------------
    private Cluster createEcsCluster() {
        return Cluster.Builder.create(this, "PatientManagementCluster")
                .vpc(vpc)
                .build();
    }

    // ------------------------------------------------------------------
    // ECS Service Factory
    // ------------------------------------------------------------------
    private FargateService createFargateService(
            String id,
            String imageName,
            List<Integer> ports,
            DatabaseInstance db,
            Map<String, String> additionalEnvVars) {

        FargateTaskDefinition taskDefinition =
                FargateTaskDefinition.Builder.create(this, id + "Task")
                        .cpu(256)
                        .memoryLimitMiB(512)
                        .build();

        Map<String, String> envVars = new HashMap<>();

        // Docker Kafka (local)
        envVars.put("SPRING_KAFKA_BOOTSTRAP_SERVERS",
                "host.docker.internal:9094");

        if (additionalEnvVars != null) {
            envVars.putAll(additionalEnvVars);
        }

        if (db != null) {
            envVars.put(
                    "SPRING_DATASOURCE_URL",
                    String.format(
                            "jdbc:postgresql://%s:%s/%s-db",
                            db.getDbInstanceEndpointAddress(),
                            db.getDbInstanceEndpointPort(),
                            imageName));
            envVars.put("SPRING_DATASOURCE_USERNAME", "admin_user");
            envVars.put(
                    "SPRING_DATASOURCE_PASSWORD",
                    Objects.requireNonNull(db.getSecret())
                            .secretValueFromJson("password")
                            .toString());
            envVars.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "update");
        }

        taskDefinition.addContainer(
                imageName + "Container",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry(imageName))
                        .environment(envVars)
                        .portMappings(
                                ports.stream()
                                        .map(
                                                p ->
                                                        PortMapping.builder()
                                                                .containerPort(p)
                                                                .protocol(Protocol.TCP)
                                                                .build())
                                        .toList())
                        .logging(
                                LogDriver.awsLogs(
                                        AwsLogDriverProps.builder()
                                                .logGroup(
                                                        LogGroup.Builder.create(this, id + "LogGroup")
                                                                .logGroupName("/ecs/" + imageName)
                                                                .removalPolicy(RemovalPolicy.DESTROY)
                                                                .retention(RetentionDays.ONE_DAY)
                                                                .build())
                                                .streamPrefix(imageName)
                                                .build()))
                        .build());

        return FargateService.Builder.create(this, id)
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .assignPublicIp(true)
                .serviceName(imageName)
                .build();
    }

    // ------------------------------------------------------------------
    // API Gateway
    // ------------------------------------------------------------------
    private void createApiGatewayService() {
        FargateTaskDefinition taskDefinition =
                FargateTaskDefinition.Builder.create(this, "APIGatewayTaskDefinition")
                        .cpu(256)
                        .memoryLimitMiB(512)
                        .build();

        taskDefinition.addContainer(
                "APIGatewayContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry("api-gateway"))
                        .environment(
                                Map.of(
                                        "SPRING_PROFILES_ACTIVE", "prod",
                                        "AUTH_SERVICE_URL",
                                        "http://host.docker.internal:4005"))
                        .portMappings(
                                List.of(
                                        PortMapping.builder()
                                                .containerPort(4004)
                                                .protocol(Protocol.TCP)
                                                .build()))
                        .logging(
                                LogDriver.awsLogs(
                                        AwsLogDriverProps.builder()
                                                .logGroup(
                                                        LogGroup.Builder.create(
                                                                        this, "ApiGatewayLogGroup")
                                                                .logGroupName("/ecs/api-gateway")
                                                                .removalPolicy(RemovalPolicy.DESTROY)
                                                                .retention(RetentionDays.ONE_DAY)
                                                                .build())
                                                .streamPrefix("api-gateway")
                                                .build()))
                        .build());

        ApplicationLoadBalancedFargateService.Builder.create(
                        this, "APIGatewayService")
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .desiredCount(1)
                .healthCheckGracePeriod(Duration.seconds(60))
                .build();
    }

    // ------------------------------------------------------------------
    // MAIN
    // ------------------------------------------------------------------
    public static void main(final String[] args) {
        App app =
                new App(AppProps.builder().outdir("./cdk.out").build());

        StackProps props =
                StackProps.builder()
                        .synthesizer(new BootstraplessSynthesizer())
                        .build();

        new LocalStack(app, "localstack", props);
        app.synth();
    }
}
