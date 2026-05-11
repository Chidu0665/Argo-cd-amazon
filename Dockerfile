# ═══════════════════════════════════════════════════════
# Multi-stage Dockerfile — Amazon JSP/Servlet App
# Stage 1 : Maven build  → produces .war file
# Stage 2 : Tomcat runtime → runs the .war
# ═══════════════════════════════════════════════════════

# ── Stage 1: Maven Build ────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Copy parent pom first (layer cache — deps only re-download if pom changes)
COPY Amazon/pom.xml .

# Copy module poms
COPY Amazon/Amazon-Core/pom.xml Amazon-Core/pom.xml
COPY Amazon/Amazon-Web/pom.xml  Amazon-Web/pom.xml

# Download all dependencies (cached layer)
RUN mvn dependency:go-offline -B -q

# Copy all source code
COPY Amazon/Amazon-Core/src Amazon-Core/src
COPY Amazon/Amazon-Web/src  Amazon-Web/src

# Build the WAR (skip unit tests — tests ran in Stage 2 of pipeline)
RUN mvn clean package -DskipTests -B && \
    echo "=== Build Output ===" && \
    find . -name "*.war" | grep target


# ── Stage 2: Tomcat Runtime ─────────────────────────────
FROM tomcat:10.1-jre17-temurin-jammy AS runtime

# Remove default Tomcat apps (security best practice)
RUN rm -rf /usr/local/tomcat/webapps/ROOT \
           /usr/local/tomcat/webapps/examples \
           /usr/local/tomcat/webapps/docs \
           /usr/local/tomcat/webapps/host-manager \
           /usr/local/tomcat/webapps/manager

# Copy the built WAR as ROOT.war so it deploys at /
COPY --from=builder /build/Amazon-Web/target/*.war \
     /usr/local/tomcat/webapps/ROOT.war

# Create a non-root user to run Tomcat
RUN groupadd -r tomcatgroup && \
    useradd  -r -g tomcatgroup -d /usr/local/tomcat -s /bin/false tomcatuser && \
    chown -R tomcatuser:tomcatgroup /usr/local/tomcat

# Docker-level healthcheck (Kubernetes probe overrides this, but good to have)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -fs http://localhost:8080/health/live || exit 1

USER tomcatuser

EXPOSE 8080

# JVM options: container-aware memory settings
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8"

CMD ["catalina.sh", "run"]