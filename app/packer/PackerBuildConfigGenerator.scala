package packer

import java.nio.file.{ Paths, Path }

import models.packer.{ PackerProvisionerConfig, PackerBuilderConfig, PackerBuildConfig }
import models.{ RoleId, Bake, Recipe }

object PackerBuildConfigGenerator {

  /**
   * Generates a Packer build config that:
   *  - starts an EC2 machine
   *  - installs Ansible
   *  - runs Ansible to install the required roles
   *  - tags the resulting AMI with the recipe ID and build number
   */
  def generatePackerBuildConfig(
    bake: Bake, playbookFile: Path, awsAccountNumbers: Seq[String])(implicit packerConfig: PackerConfig): PackerBuildConfig = {
    val variables = Map(
      "recipe" -> bake.recipe.id.value,
      "base_image_ami_id" -> bake.recipe.baseImage.amiId.value,
      "build_number" -> bake.buildNumber.toString,
      "aws_account_numbers" -> awsAccountNumbers.mkString(",")
    )
    val builder = PackerBuilderConfig(
      name = "{{user `recipe`}}",
      `type` = "amazon-ebs",
      region = "eu-west-1",
      vpc_id = packerConfig.vpcId,
      subnet_id = packerConfig.subnetId,
      source_ami = "{{user `base_image_ami_id`}}",
      instance_type = "t2.micro",
      ssh_username = "ubuntu",
      run_tags = Map(
        "Stage" -> "INFRA",
        "Stack" -> "amigo-packer",
        "App" -> "{{user `recipe`}}"
      ),
      ami_name = "amigo_{{user `recipe`}}_{{user `build_number`}}_{{isotime \"2006/01/02_15-04-05\"}}",
      ami_description = "AMI for {{user `recipe`}} built by Amigo: #{{user `build_number`}}",
      ami_users = "{{user `aws_account_numbers`}}",
      iam_instance_profile = packerConfig.instanceProfile,
      tags = Map(
        "BuiltBy" -> "amigo",
        "AmigoStage" -> packerConfig.stage,
        "Name" -> "amigo_{{user `recipe`}}_{{user `build_number`}}_{{isotime \"2006/01/02_15-04-05\"}}",
        "Recipe" -> "{{user `recipe`}}",
        "BuildNumber" -> "{{user `build_number`}}",
        "SourceAMI" -> "{{user `base_image_ami_id`}}"
      )
    )
    val provisioners = Seq(
      // bootstrap Ansible
      PackerProvisionerConfig.executeRemoteCommands(Seq(
        // Wait for cloud-init to finish first: https://github.com/mitchellh/packer/issues/2639
        "while [ ! -f /var/lib/cloud/instance/boot-finished ]; do echo 'Waiting for cloud-init...'; sleep 1; done",
        "apt-get --yes install software-properties-common",
        "apt-add-repository ppa:ansible/ansible",
        "apt-get --yes update",
        "apt-get --yes install ansible"
      )),
      // Use ansible to install roles
      PackerProvisionerConfig.ansibleLocal(playbookFile, Paths.get("roles"))
    )
    PackerBuildConfig(
      variables,
      Seq(builder),
      provisioners
    )
  }

}
