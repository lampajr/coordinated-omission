NOW := $$(date "+%y%m%d-%H%M%S")
PROFILING := 8
EVENT := cpu
FORMAT := html

.PHONY: rm-hist
rm-hist:
	rm -rf hist/response* hist/service*

.PHONY: clean
clean:
	mvn clean

.PHONY: build
build:
	mvn clean package

.PHONY: usage
usage:
	@echo "Usage: "
	@echo "		java -jar target/coordinated-omission-*-all.jar [ARGS...]"
	@echo "		make [ARGS='...'] run"
	@echo "Args:"
	@echo "		Positional arguments"
	@echo "		1) RPS (requests per seconds), default is 10_000."
	@echo "		2) Test/load duration in seconds, default is 10 s."
	@echo "		3) Single request duration in nanoseconds, default is 1_000 ns."
	@echo "		4) Server delay in nanoseconds, default is 10_000 ns."
	@echo "		5) Server delay frequency in percentage, default is 0%."
	@echo "		6) Wait till version, 'classic', 'vanilla' or the default."
	@echo "Examples:"
	@echo "		java -jar target/coordinated-omission-*-all.jar 1000 20"
	@echo "		make ARGS='1000 10 100 6000000 0 classic' run"

.PHONY: run
run:
	java -XX:TieredStopAtLevel=1 -jar target/coordinated-omission-*-all.jar ${ARGS}

# .PHONY: profile
# profile:
# 	sudo sysctl kernel.perf_event_paranoid=1 ;\
# 	sudo sysctl kernel.kptr_restrict=0 ;\
# 	java -jar target/coordinated-omission-*-all.jar ${ARGS} & \
# 	pid=$$! ;\
# 	echo "Coordinated omission example running with pid $$pid" ;\
# 	java -jar ap-loader-all.jar profiler -e ${EVENT} -t -d ${PROFILING} -f ${NOW}_${EVENT}.${FORMAT} $$pid