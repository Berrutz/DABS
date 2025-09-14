FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

# UTF-8 locale (without installing extra packages)
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# 1) Install dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl wget unzip gnupg ca-certificates \
    openjdk-8-jdk-headless bash ca-certificates coreutils findutils \
 && rm -rf /var/lib/apt/lists/*

# 2b) SWI‑Prolog and the Java interface (JPL)
RUN apt-get update && \
    apt-get install -y swi-prolog swi-prolog-java && \
    apt-get clean

# 3) NVM, Node.js 18.13.0 and npm 9.2.0
ENV NVM_DIR=/root/.nvm
RUN curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash \
&& . "$NVM_DIR/nvm.sh" \
&& nvm install 18.13.0 \
&& nvm use 18.13.0 \
&& npm install -g npm@9.2.0

ENV PATH="$NVM_DIR/versions/node/v18.13.0/bin:$PATH"

# Version checks
RUN java -version && node -v && npm -v && curl --version
    
# 4a) JPL variables
ENV CLASSPATH="/usr/lib/swi-prolog/lib/jpl.jar:/app/lib/jade.jar:."
ENV LD_LIBRARY_PATH="/usr/lib/swi-prolog/lib/x86_64-linux:/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/amd64/server"
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

WORKDIR /app
RUN npm install --no-audit --no-fund --silent ws
COPY ./ /app
EXPOSE 1099 7778 4000 5000 5001 5002 4100

# Fix CRLF and make scripts executable
RUN sed -i 's/\r$//' /app/docker/*.sh || true

RUN chmod +x /app/docker/start_main.sh /app/docker/start_agent.sh /app/docker/entrypoint.sh

# 🚀 Real ENTRYPOINT
ENTRYPOINT ["bash", "-lc", "./docker/entrypoint.sh"]
