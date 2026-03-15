# JOTP Books

Comprehensive documentation for the JOTP framework, organized by audience and learning path.

## 📚 Available Books

### [JOTPOps: Engineering Java Applications](./jotpops/)
**DevOps, production deployment, and end-to-end software delivery**

Targeted at Java engineers who want to own the complete software delivery lifecycle. This book combines JOTP's fault-tolerance with modern DevOps practices: infrastructure-as-code, containerization, CI/CD, distributed deployment, and production observability.

**Audience:** Java platform engineers, DevOps engineers, SREs, technical leads
**Prerequisites:** Java 26, Docker, Terraform, AWS basics
**Sample Application:** TaskFlow - production Kanban board on AWS Docker Swarm

**Key Topics:**
- Infrastructure-as-code with Terraform
- Docker containerization and multi-stage builds
- GitHub Actions CI/CD pipelines
- AWS deployment (EC2, Docker Swarm, ALB, Auto Scaling)
- Distributed JOTP with gRPC
- Prometheus + Grafana observability
- Production monitoring and alerting

[→ Read JOTPOps Book](./jotpops/)

---

### [Designing Java Systems with JOTP](./jotp-patterns/)
**Design patterns, architectural patterns, and concurrency patterns**

Targeted at Java architects and senior developers who want to master OTP design patterns. This book focuses on the structural and behavioral patterns that make JOTP systems scalable, maintainable, and fault-tolerant.

**Audience:** Java architects, senior developers, system designers
**Prerequisites:** Java 26, JOTP basics, concurrency fundamentals
**Focus:** Pattern catalog with code examples and architectural guidance

**Key Topics:**
- Supervision tree patterns
- State machine patterns
- Event-driven patterns
- Fault-tolerance patterns
- Distribution patterns
- Testing patterns

[→ Read Design Patterns Book](./jotp-patterns/)

---

## 🎯 Learning Path Recommendations

### For New Java Engineers (0-2 years experience)
1. Start with **Designing Java Systems with JOTP** to understand patterns
2. Then read **JOTPOps** for production deployment skills

### For Experienced Java Engineers (2+ years experience)
1. Start with **JOTPOps** if you need DevOps skills
2. Reference **Designing Java Systems with JOTP** for specific patterns

### For Platform/SRE Engineers
1. Focus on **JOTPOps** for end-to-end delivery pipeline
2. Reference design patterns book for understanding JOTP architecture

### For Architects/Technical Leads
1. Read both books in parallel
2. Use **JOTPOps** for deployment strategy
3. Use **Designing Java Systems with JOTP** for architectural patterns

---

## 🔗 Cross-References

The books complement each other:

- **JOTPOps Chapter 3** (Build and Package) → **Design Patterns: State Machine Pattern**
- **JOTPOps Chapter 9** (Multi-Node Swarm) → **Design Patterns: Distribution Patterns**
- **JOTPOps Chapter 10** (Distributed JOTP) → **Design Patterns: Fault-Tolerance Patterns**

---

## 📖 Building the Books

Both books use [mdBook](https://rust-lang.github.io/mdBook/):

```bash
# Install mdBook
cargo install mdbook

# Build JOTPOps book
cd books/jotpops
mdbook build

# Build Design Patterns book
cd books/jotp-patterns
mdbook build

# Serve locally for development
mdbook serve
```

---

## 🤝 Contributing

When contributing documentation:

1. **JOTPOps book updates** → Edit in `books/jotpops/src/`
2. **Design Patterns updates** → Edit in `books/jotp-patterns/src/`
3. **Cross-cutting concepts** → Add to both books with cross-references

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines.

---

## 📝 Book Status

| Book | Status | Last Updated |
|------|--------|--------------|
| JOTPOps | Beta B1.0 | 2026-03-12 |
| Design Patterns | Outline | 2026-03-15 |

---

## 🌐 Resources

- **JOTP Framework**: [github.com/seanchatmangpt/jotp](https://github.com/seanchatmangpt/jotp)
- **Javadoc**: [javadoc.io/doc/io.github.seanchatmangpt/jotp](https://javadoc.io/doc/io.github.seanchatmangpt/jotp)
- **Issues**: [github.com/seanchatmangpt/jotp/issues](https://github.com/seanchatmangpt/jotp/issues)
