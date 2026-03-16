# JOTP Spring Boot Integration

This subproject contains examples of integrating JOTP with Spring Boot applications.

## Purpose

The Spring Boot integration examples are kept separate from the main JOTP project to ensure:
- **No Spring dependencies in JOTP core** - JOTP remains a pure Java 26 framework
- **Modular integration** - Users can choose to add Spring integration independently
- **Clean separation** - Enterprise users can use JOTP without Spring overhead

## Running the Examples

```bash
cd spring-boot-integration
mvn spring-boot:run
```

## Integration Pattern

The examples demonstrate:
1. **Order State Machines** - Using JOTP StateMachine for order workflows
2. **Supervision Trees** - Spring-managed JOTP supervisors
3. **Event Handling** - Bridging Spring events with JOTP processes

## Architecture

```
Spring Boot App
    ↓
JOTP Integration Layer
    ↓
JOTP Core (pure Java 26, no Spring deps)
```
