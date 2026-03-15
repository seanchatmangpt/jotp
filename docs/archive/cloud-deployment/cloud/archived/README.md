# Archived Cloud Platform Documentation

This directory contains documentation for cloud platforms that are **not yet implemented** in JOTP.

## ⚠️ Important Notice

The platforms documented here are **planned but not yet supported**. The documentation is provided for:

- Future implementation reference
- Community contribution guidance
- Architectural planning

## Archived Platforms

### IBM Cloud
- **Status:** 📋 Planned
- **Documentation:** [ibm-cloud/](./ibm-cloud/)
- **Reason for archiving:** Infrastructure code not yet implemented
- **Estimated timeline:** Q3 2026

### Red Hat OpenShift
- **Status:** 📋 Planned
- **Documentation:** [openshift/](./openshift/)
- **Reason for archiving:** Container deployment strategy under evaluation
- **Estimated timeline:** Q4 2026

## How to Contribute

If you're interested in helping bring these platforms to production status:

1. **Review the existing documentation** in the platform-specific subdirectory
2. **Test deployment configurations** in a development environment
3. **Open a GitHub issue** to coordinate implementation efforts
4. **Submit a pull request** with:
   - Infrastructure code (Terraform/Packer configurations)
   - Updated documentation with implementation notes
   - Test results from deployment verification

## Implementation Checklist

For each archived platform, the following are required for beta status:

- [ ] Terraform configurations tested and working
- [ ] Packer image builder (if applicable)
- [ ] Getting started tutorial completed
- [ ] Production deployment guide verified
- [ ] Documentation updated with implementation status
- [ ] At least one successful deployment test

## Questions?

- **General questions:** [GitHub Discussions](https://github.com/seanchatmangpt/jotp/discussions)
- **Implementation issues:** [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Contribution guidelines:** See main repository `CONTRIBUTING.md`
