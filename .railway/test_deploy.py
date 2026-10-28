import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import deploy


IMAGE = "niteshbalusu/noah-server@sha256:" + "a" * 64


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.state = {
            "serviceInstance": {
                "serviceId": deploy.SERVICES["api"][0],
                "environmentId": deploy.ENVIRONMENT_ID,
                "service": {"projectId": deploy.PROJECT_ID},
                "source": {"image": IMAGE},
            }
        }
        self.deployment = {
            "deployment": {
                "id": "release-1", "status": "SUCCESS",
                "projectId": deploy.PROJECT_ID,
                "environmentId": deploy.ENVIRONMENT_ID,
                "serviceId": deploy.SERVICES["api"][0],
            }
        }
        self.gql = self.enterContext(patch.object(deploy, "graphql"))
        self.health = self.enterContext(patch.object(deploy.urllib.request, "urlopen"))
        self.health.return_value.__enter__.return_value.status = 200
        self.sleep = self.enterContext(patch.object(deploy.time, "sleep"))

    def responses(self, *statuses):
        self.gql.side_effect = [
            self.state, {"serviceInstanceUpdate": True},
            {"serviceInstanceDeployV2": "release-1"}, *statuses,
        ]

    def test_waits_for_the_new_deployment_and_only_changes_its_image(self):
        pending = {"deployment": {**self.deployment["deployment"], "status": "DEPLOYING"}}
        self.responses(pending, self.deployment)
        self.assertEqual(deploy.deploy("api", IMAGE), "release-1")
        update = self.gql.call_args_list[1]
        self.assertEqual(update.kwargs, {
            "environment": deploy.ENVIRONMENT_ID,
            "service": deploy.SERVICES["api"][0], "image": IMAGE,
        })
        self.assertNotIn("variables", update.args[0])
        self.assertNotIn("volume", update.args[0])
        self.assertNotIn("latestDeployment", self.gql.call_args_list[-1].args[0])
        self.assertEqual(self.gql.call_args_list[-1].kwargs, {"id": "release-1"})
        self.sleep.assert_called_once_with(10)
        self.health.assert_called_once_with("https://signet.noahwallet.io/health", timeout=30)

    def test_rejects_unpinned_or_unexpected_images_before_contacting_railway(self):
        for image in ["niteshbalusu/noah-server:latest", "other/server@sha256:" + "a" * 64,
                      "niteshbalusu/noah-barkd@sha256:" + "a" * 64, IMAGE + ";echo unsafe"]:
            with self.subTest(image=image), self.assertRaises(ValueError):
                deploy.deploy("api", image)
        self.gql.assert_not_called()

    def test_wrong_target_never_reaches_a_mutation(self):
        self.state["serviceInstance"]["environmentId"] = "production"
        self.gql.return_value = self.state
        with self.assertRaisesRegex(RuntimeError, "outside"):
            deploy.deploy("api", IMAGE)
        self.assertEqual(self.gql.call_count, 1)

    def test_rejected_update_does_not_deploy(self):
        self.gql.side_effect = [self.state, {"serviceInstanceUpdate": False}]
        with self.assertRaisesRegex(RuntimeError, "refused"):
            deploy.deploy("api", IMAGE)
        self.assertEqual(self.gql.call_count, 2)

    def test_failed_rollout_does_not_report_success_from_old_public_health(self):
        failed = {"deployment": {**self.deployment["deployment"], "status": "FAILED"}}
        self.responses(failed)
        with self.assertRaisesRegex(RuntimeError, "did not succeed"):
            deploy.deploy("api", IMAGE)
        self.health.assert_not_called()

    def test_timeout_is_a_failure(self):
        self.responses()
        with patch.object(deploy.time, "monotonic", side_effect=[0, 601]):
            with self.assertRaises(TimeoutError):
                deploy.deploy("api", IMAGE)
        self.health.assert_not_called()

    def test_public_health_failure_fails_the_release(self):
        self.responses(self.deployment)
        self.health.return_value.__enter__.return_value.status = 503
        with self.assertRaisesRegex(RuntimeError, "healthcheck failed"):
            deploy.deploy("api", IMAGE)

    def test_barkd_uses_the_existing_barkd_service(self):
        self.state["serviceInstance"]["serviceId"] = deploy.SERVICES["barkd"][0]
        self.state["serviceInstance"]["source"]["image"] = IMAGE.replace("noah-server", "noah-barkd")
        self.deployment["deployment"]["serviceId"] = deploy.SERVICES["barkd"][0]
        self.responses(self.deployment)
        deploy.deploy("barkd", IMAGE.replace("noah-server", "noah-barkd"))
        self.assertEqual(self.gql.call_args_list[1].kwargs["service"], deploy.SERVICES["barkd"][0])


class LiveImageTests(unittest.TestCase):
    def test_infrastructure_reads_current_pins_and_refuses_missing_or_untrusted_images(self):
        module = Path(__file__).with_name("live-images.ts").resolve().as_uri()
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "railway"
            executable.write_text('#!/usr/bin/env python3\nimport os\nprint(os.environ["FIXTURE"])\n')
            executable.chmod(0o700)
            env = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"]}
            for image in [IMAGE, IMAGE.replace("a" * 64, "b" * 64), None,
                          "other/server@sha256:" + "a" * 64]:
                fixture = {"data": {"api": {"source": {"image": image}},
                                    "barkd": {"source": {"image": IMAGE.replace("noah-server", "noah-barkd")}}}}
                env["FIXTURE"] = json.dumps(fixture)
                result = subprocess.run(
                    ["node", "--experimental-strip-types", "--input-type=module", "--eval",
                     f'import {{liveImages}} from "{module}"; process.stdout.write(JSON.stringify(liveImages()));'],
                    env=env, capture_output=True, text=True,
                )
                with self.subTest(image=image):
                    if image and image.startswith("niteshbalusu/"):
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertEqual(json.loads(result.stdout)["api"], image)
                    else:
                        self.assertNotEqual(result.returncode, 0)


class GraphQLTests(unittest.TestCase):
    def test_partial_graphql_errors_are_not_treated_as_success(self):
        with patch.object(deploy.subprocess, "run") as run:
            run.return_value.stdout = json.dumps({"data": {}, "errors": [{"message": "denied"}]})
            with self.assertRaisesRegex(RuntimeError, "GraphQL request failed"):
                deploy.graphql("query { example }")

    def test_cli_failure_aborts_the_release(self):
        with patch.object(deploy.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "railway")):
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.graphql("query { example }")


if __name__ == "__main__":
    unittest.main()
