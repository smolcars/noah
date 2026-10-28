#!/usr/bin/env python3
"""Release one existing signet service without applying infrastructure changes."""

import argparse
import json
import re
import subprocess
import time
import urllib.request


PROJECT_ID = "8847a6a1-bf07-4321-a62b-9839e9954e46"
ENVIRONMENT_ID = "37880684-30ae-4242-af12-ddba1ba620ad"
SERVICES = {
    "api": ("2dac716c-bcd0-4c2c-ab77-6f3226548ca8", "noah-server"),
    "barkd": ("0250c7b1-1916-44e0-81e7-afc947c4c5ad", "noah-barkd"),
}


def graphql(query, **variables):
    result = subprocess.run(
        ["railway", "api", query, "--variables", json.dumps(variables), "--compact"],
        check=True, capture_output=True, text=True, timeout=60,
    )
    response = json.loads(result.stdout)
    if response.get("errors"):
        raise RuntimeError("Railway GraphQL request failed")
    return response["data"]


def deploy(service, image):
    service_id, repository = SERVICES[service]
    if not re.fullmatch(rf"niteshbalusu/{repository}@sha256:[a-f0-9]{{64}}", image):
        raise ValueError("An immutable digest from the expected Noah image repository is required")

    state = graphql(
        """query($environment: String!, $service: String!) {
          serviceInstance(environmentId: $environment, serviceId: $service) {
            serviceId environmentId source { image }
            service { projectId }
          }
        }""",
        environment=ENVIRONMENT_ID, service=service_id,
    )["serviceInstance"]
    if (
        state["service"]["projectId"] != PROJECT_ID
        or state["environmentId"] != ENVIRONMENT_ID
        or state["serviceId"] != service_id
    ):
        raise RuntimeError("Refusing to deploy outside the existing signet service")

    print(f"Previous {service} image: {state['source']['image']}", flush=True)
    # Update only the image. In particular, do not apply the project graph or touch volumes/variables.
    updated = graphql(
        """mutation($environment: String!, $service: String!, $image: String!) {
          serviceInstanceUpdate(environmentId: $environment, serviceId: $service,
            input: { source: { image: $image } })
        }""",
        environment=ENVIRONMENT_ID, service=service_id, image=image,
    )["serviceInstanceUpdate"]
    if not updated:
        raise RuntimeError("Railway refused the image update")
    deployment_id = graphql(
        """mutation($environment: String!, $service: String!) {
          serviceInstanceDeployV2(environmentId: $environment, serviceId: $service)
        }""",
        environment=ENVIRONMENT_ID, service=service_id,
    )["serviceInstanceDeployV2"]
    print(f"Deploying {image}: {deployment_id}", flush=True)

    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        deployment = graphql(
            """query($id: String!) { deployment(id: $id) {
              id status projectId environmentId serviceId
            } }""", id=deployment_id,
        )["deployment"]
        if (
            deployment["projectId"] != PROJECT_ID
            or deployment["environmentId"] != ENVIRONMENT_ID
            or deployment["serviceId"] != service_id
        ):
            raise RuntimeError("Unexpected deployment target")
        status = deployment["status"]
        print(f"{deployment_id}: {status}", flush=True)
        if status == "SUCCESS":
            with urllib.request.urlopen("https://signet.noahwallet.io/health", timeout=30) as health:
                if health.status != 200:
                    raise RuntimeError("Public signet healthcheck failed")
            return deployment_id
        if status in {"FAILED", "CRASHED", "REMOVED", "SKIPPED", "CANCELED"}:
            raise RuntimeError(f"Deployment {deployment_id} did not succeed: {status}")
        time.sleep(10)
    raise TimeoutError(f"Deployment {deployment_id} did not become healthy in 10 minutes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=SERVICES)
    parser.add_argument("image")
    args = parser.parse_args()
    deploy(args.service, args.image)
