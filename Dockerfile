FROM eclipse-temurin:21-jre

WORKDIR /app

COPY build/install/lsmite .

EXPOSE 1337

ENTRYPOINT ["bin/lsmite", "--port=1337", "--memtable-size=50"]