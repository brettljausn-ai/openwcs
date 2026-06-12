package org.openwcs.process.api;

import java.util.List;
import java.util.Map;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.openwcs.process.api.ProcessDtos.TaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** User/wait tasks for running BPMN instances (build.md §7): list and complete. */
@RestController
@RequestMapping("/api/process/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public List<TaskView> list(@RequestParam(required = false) String processInstanceId,
                               @RequestParam(required = false) String assignee) {
        TaskQuery query = tasks.createTaskQuery();
        if (processInstanceId != null) {
            query = query.processInstanceId(processInstanceId);
        }
        if (assignee != null) {
            query = query.taskAssignee(assignee);
        }
        return query.orderByTaskCreateTime().asc().list().stream()
                .map(t -> new TaskView(t.getId(), t.getName(), t.getAssignee(), t.getProcessInstanceId()))
                .toList();
    }

    @PostMapping("/{id}/complete")
    public void complete(@PathVariable String id, @RequestBody(required = false) Map<String, Object> variables) {
        Task task = tasks.createTaskQuery().taskId(id).singleResult();
        tasks.complete(id, variables == null ? Map.of() : variables);
        if (task != null) {
            log.info("user task '{}' ({}) completed for process instance {}: the process advances past this wait state",
                    task.getName(), id, task.getProcessInstanceId());
        }
    }
}
