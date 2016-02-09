/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.engine.impl.persistence.entity;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.activiti.bpmn.model.ActivitiListener;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.ImplementationType;
import org.activiti.bpmn.model.UserTask;
import org.activiti.engine.ActivitiException;
import org.activiti.engine.compatibility.Activiti5CompatibilityHandler;
import org.activiti.engine.delegate.DelegateTask;
import org.activiti.engine.delegate.TaskListener;
import org.activiti.engine.delegate.event.ActivitiEventType;
import org.activiti.engine.delegate.event.impl.ActivitiEventBuilder;
import org.activiti.engine.impl.TaskQueryImpl;
import org.activiti.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.activiti.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.delegate.invocation.TaskListenerInvocation;
import org.activiti.engine.impl.persistence.entity.data.DataManager;
import org.activiti.engine.impl.persistence.entity.data.TaskDataManager;
import org.activiti.engine.impl.util.Activiti5Util;
import org.activiti.engine.impl.util.ProcessDefinitionUtil;
import org.activiti.engine.task.IdentityLinkType;
import org.activiti.engine.task.Task;
import org.apache.commons.lang3.StringUtils;

/**
 * @author Tom Baeyens
 * @author Joram Barrez
 */
public class TaskEntityManagerImpl extends AbstractEntityManager<TaskEntity> implements TaskEntityManager {
  
  protected TaskDataManager taskDataManager;
  
  public TaskEntityManagerImpl(ProcessEngineConfigurationImpl processEngineConfiguration, TaskDataManager taskDataManager) {
    super(processEngineConfiguration);
    this.taskDataManager = taskDataManager;
  }
  
  @Override
  protected DataManager<TaskEntity> getDataManager() {
    return taskDataManager;
  }
  
  @Override
  public TaskEntity create() {
    TaskEntity taskEntity = super.create();
    taskEntity.setCreateTime(getClock().getCurrentTime());
    return taskEntity;
  }
  
  @Override
  public void insert(TaskEntity entity, boolean fireCreateEvent) {
    super.insert(entity, fireCreateEvent);
    getHistoryManager().recordTaskId(entity);
  }
  
  @Override
  public void insert(TaskEntity taskEntity, ExecutionEntity execution) {

    // Inherit tenant id (if applicable)
    if (execution != null && execution.getTenantId() != null) {
      taskEntity.setTenantId(execution.getTenantId());
    }

    if (execution != null) {
      execution.getTasks().add(taskEntity);
      taskEntity.setExecutionId(execution.getId());
      taskEntity.setProcessInstanceId(execution.getProcessInstanceId());
      taskEntity.setProcessDefinitionId(execution.getProcessDefinitionId());
      
      getHistoryManager().recordTaskExecutionIdChange(taskEntity.getId(), taskEntity.getExecutionId());
    }
    
    super.insert(taskEntity, true);

    getHistoryManager().recordTaskCreated(taskEntity, execution);
  }
  
  @Override
  public TaskEntity update(TaskEntity taskEntity) {
    
    HistoricTaskInstanceEntity historicTaskInstanceEntity = getHistoricTaskInstanceEntityManager().findById(taskEntity.getId());
    String originalName = null;
    String originalAssignee = null;
    String originalOwner = null;
    String originalDescription = null;
    Date originalDueDate = null;
    int originalPriority = -1;
    String originalCategory = null;
    String originalFormKey = null;
    String originalParentTaskId = null;
    String originalTaskDefinitionKey = null;
    
    if (historicTaskInstanceEntity != null) {
      
      originalName = historicTaskInstanceEntity.getName();
      originalAssignee = historicTaskInstanceEntity.getAssignee();
      originalOwner = historicTaskInstanceEntity.getOwner();
      originalDescription = historicTaskInstanceEntity.getDescription();
      originalDueDate = historicTaskInstanceEntity.getDueDate();
      originalPriority = historicTaskInstanceEntity.getPriority();
      originalCategory = historicTaskInstanceEntity.getCategory();
      originalFormKey = historicTaskInstanceEntity.getFormKey();
      originalParentTaskId = historicTaskInstanceEntity.getParentTaskId();
      originalTaskDefinitionKey = historicTaskInstanceEntity.getTaskDefinitionKey();
      
    } else {
      
      TaskEntity originalTaskEntity = taskDataManager.findById(taskEntity.getId(), false);
      
      if (originalTaskEntity == null) {
        originalTaskEntity = taskDataManager.findById(taskEntity.getId());
      }
      
      if (originalTaskEntity != null) {
        originalName = originalTaskEntity.getName();
        originalAssignee = originalTaskEntity.getAssignee();
        originalOwner = originalTaskEntity.getOwner();
        originalDescription = originalTaskEntity.getDescription();
        originalDueDate = originalTaskEntity.getDueDate();
        originalPriority = originalTaskEntity.getPriority();
        originalCategory = originalTaskEntity.getCategory();
        originalFormKey = originalTaskEntity.getFormKey();
        originalParentTaskId = originalTaskEntity.getParentTaskId();
        originalTaskDefinitionKey = originalTaskEntity.getTaskDefinitionKey();
      }
      
    }
    
    if (!StringUtils.equals(originalName, taskEntity.getName())) {
      getHistoryManager().recordTaskNameChange(taskEntity.getId(), taskEntity.getName());
    }
    
    if (!StringUtils.equals(originalOwner, taskEntity.getOwner())) {
      updateOwner(taskEntity, taskEntity.getOwner());
    }
    
    if (!StringUtils.equals(originalAssignee, taskEntity.getAssignee())) {
      updateAssignee(taskEntity, taskEntity.getAssignee(), true);
    }
    
    if (!StringUtils.equals(originalDescription, taskEntity.getDescription())) {
      getHistoryManager().recordTaskDescriptionChange(taskEntity.getId(), taskEntity.getDescription());
    }
    
    if ( (originalDueDate == null && taskEntity.getDueDate() != null) 
        || (originalDueDate != null && taskEntity.getDueDate() == null)
        || (originalDueDate != null && !originalDueDate.equals(taskEntity.getDueDate())) ) {
      getHistoryManager().recordTaskDueDateChange(taskEntity.getId(), taskEntity.getDueDate());
    }
    
    if (originalPriority != taskEntity.getPriority()) {
      getHistoryManager().recordTaskPriorityChange(taskEntity.getId(), taskEntity.getPriority());
    }
    
    if (!StringUtils.equals(originalCategory, taskEntity.getCategory())) {
      getHistoryManager().recordTaskCategoryChange(taskEntity.getId(), taskEntity.getCategory());
    }
    
    if (!StringUtils.equals(originalFormKey, taskEntity.getFormKey())) {
      getHistoryManager().recordTaskFormKeyChange(taskEntity.getId(), taskEntity.getFormKey());
    }
    
    if (!StringUtils.equals(originalParentTaskId, taskEntity.getParentTaskId())) {
      getHistoryManager().recordTaskParentTaskIdChange(taskEntity.getId(), taskEntity.getParentTaskId());
    }
    
    if (!StringUtils.equals(originalTaskDefinitionKey, taskEntity.getTaskDefinitionKey())) {
      getHistoryManager().recordTaskDefinitionKeyChange(taskEntity.getId(), taskEntity.getTaskDefinitionKey());
    }
    
    boolean fireEvent = taskEntity.getRevision() > 0;
    
    return super.update(taskEntity, fireEvent);
  }

  protected void updateAssignee(TaskEntity taskEntity, String assignee, boolean dispatchAssignmentEvent) {

    getHistoryManager().recordTaskAssigneeChange(taskEntity.getId(), assignee);
    
    if (assignee != null && taskEntity.getProcessInstance() != null) {
      getIdentityLinkEntityManager().involveUser(taskEntity.getProcessInstance(), assignee, IdentityLinkType.PARTICIPANT);
    }
    
    fireTaskListenerEvent(taskEntity, TaskListener.EVENTNAME_ASSIGNMENT);
    getHistoryManager().recordTaskAssignment(taskEntity);

    if (getEventDispatcher().isEnabled()) {
      if (dispatchAssignmentEvent) {
        getEventDispatcher().dispatchEvent(ActivitiEventBuilder.createEntityEvent(ActivitiEventType.TASK_ASSIGNED, taskEntity));
      }

    }

  }
  
  protected void updateOwner(TaskEntity taskEntity, String owner) {
    if (owner == null && taskEntity.getOwner() == null) {
      return;
    }
    
    getHistoryManager().recordTaskOwnerChange(taskEntity.getId(), owner);
    
    if (owner != null && taskEntity.getProcessInstanceId() != null) {
      Context.getCommandContext().getIdentityLinkEntityManager().involveUser(taskEntity.getProcessInstance(), owner, IdentityLinkType.PARTICIPANT);
    }
  }
  
  @Override
  public void fireTaskListenerEvent(TaskEntity taskEntity, String taskEventName) {
    
    if (taskEntity.getProcessDefinitionId() != null) {
      
      org.activiti.bpmn.model.Process process = ProcessDefinitionUtil.getProcess(taskEntity.getProcessDefinitionId());
      FlowElement flowElement = process.getFlowElement(taskEntity.getTaskDefinitionKey());
      if (flowElement != null && flowElement instanceof UserTask) {
        UserTask userTask = (UserTask) flowElement;
        for (ActivitiListener activitiListener : userTask.getTaskListeners()) {
          String event = activitiListener.getEvent();
          if (event.equals(taskEventName) || event.equals(TaskListener.EVENTNAME_ALL_EVENTS)) {
            TaskListener taskListener = createTaskListener(activitiListener, taskEventName);
            ExecutionEntity execution = taskEntity.getExecution();
            if (execution != null) {
              taskEntity.setEventName(taskEventName);
            }
            try {
              getProcessEngineConfiguration().getDelegateInterceptor().handleInvocation(new TaskListenerInvocation(taskListener, (DelegateTask) taskEntity));
            } catch (Exception e) {
              throw new ActivitiException("Exception while invoking TaskListener: " + e.getMessage(), e);
            }
          }
        }
      }
        
    }
  }
  
  protected TaskListener createTaskListener(ActivitiListener activitiListener, String taskId) {
    TaskListener taskListener = null;

    if (ImplementationType.IMPLEMENTATION_TYPE_CLASS.equalsIgnoreCase(activitiListener.getImplementationType())) {
      taskListener = getProcessEngineConfiguration().getListenerFactory().createClassDelegateTaskListener(activitiListener);
    } else if (ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION.equalsIgnoreCase(activitiListener.getImplementationType())) {
      taskListener = getProcessEngineConfiguration().getListenerFactory().createExpressionTaskListener(activitiListener);
    } else if (ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION.equalsIgnoreCase(activitiListener.getImplementationType())) {
      taskListener = getProcessEngineConfiguration().getListenerFactory().createDelegateExpressionTaskListener(activitiListener);
    } else if (ImplementationType.IMPLEMENTATION_TYPE_INSTANCE.equalsIgnoreCase(activitiListener.getImplementationType())) {
      taskListener = (TaskListener) activitiListener.getInstance();
    }
    return taskListener;
  }

  @Override
  public void deleteTasksByProcessInstanceId(String processInstanceId, String deleteReason, boolean cascade) {
    List<TaskEntity> tasks = findTasksByProcessInstanceId(processInstanceId);

    String reason = (deleteReason == null || deleteReason.length() == 0) ? TaskEntity.DELETE_REASON_DELETED : deleteReason;

    for (TaskEntity task : tasks) {
      if (getEventDispatcher().isEnabled()) {
        getEventDispatcher().dispatchEvent(
                ActivitiEventBuilder.createActivityCancelledEvent(task.getExecution().getActivityId(), task.getName(), task.getExecutionId(), task.getProcessInstanceId(),
                    task.getProcessDefinitionId(), "userTask", UserTaskActivityBehavior.class.getName(), deleteReason));
      }

      deleteTask(task, reason, cascade, false);
    }
  }
  
  @Override
  public void deleteTask(TaskEntity task, String deleteReason, boolean cascade, boolean cancel) {
    if (!task.isDeleted()) {
      fireTaskListenerEvent(task, TaskListener.EVENTNAME_DELETE);
      task.setDeleted(true);

      String taskId = task.getId();

      List<Task> subTasks = findTasksByParentTaskId(taskId);
      for (Task subTask : subTasks) {
        deleteTask((TaskEntity) subTask, deleteReason, cascade, cancel);
      }

      getIdentityLinkEntityManager().deleteIdentityLinksByTaskId(taskId);
      getVariableInstanceEntityManager().deleteVariableInstanceByTask(task);

      if (cascade) {
        getHistoricTaskInstanceEntityManager().delete(taskId);
      } else {
        getHistoryManager().recordTaskEnd(taskId, deleteReason);
      }

      delete(task, false);

      if (getEventDispatcher().isEnabled()) {
        if (cancel) {
          getEventDispatcher().dispatchEvent(
                  ActivitiEventBuilder.createActivityCancelledEvent(task.getExecution() != null ? task.getExecution().getActivityId() : null, 
                      task.getName(), task.getExecutionId(), 
                      task.getProcessInstanceId(),
                      task.getProcessDefinitionId(), 
                      "userTask", 
                      UserTaskActivityBehavior.class.getName(), 
                      deleteReason));
        }
        
        getEventDispatcher().dispatchEvent(ActivitiEventBuilder.createEntityEvent(ActivitiEventType.ENTITY_DELETED, task));
      }
    }
  }

  @Override
  public List<TaskEntity> findTasksByExecutionId(String executionId) {
    return taskDataManager.findTasksByExecutionId(executionId);
  }

  @Override
  public List<TaskEntity> findTasksByProcessInstanceId(String processInstanceId) {
    return taskDataManager.findTasksByProcessInstanceId(processInstanceId);
  }

  @Override
  public List<Task> findTasksByQueryCriteria(TaskQueryImpl taskQuery) {
    return taskDataManager.findTasksByQueryCriteria(taskQuery);
  }

  @Override
  public List<Task> findTasksAndVariablesByQueryCriteria(TaskQueryImpl taskQuery) {
    return taskDataManager.findTasksAndVariablesByQueryCriteria(taskQuery);
  }

  @Override
  public long findTaskCountByQueryCriteria(TaskQueryImpl taskQuery) {
    return taskDataManager.findTaskCountByQueryCriteria(taskQuery);
  }

  @Override
  public List<Task> findTasksByNativeQuery(Map<String, Object> parameterMap, int firstResult, int maxResults) {
    return taskDataManager.findTasksByNativeQuery(parameterMap, firstResult, maxResults);
  }

  @Override
  public long findTaskCountByNativeQuery(Map<String, Object> parameterMap) {
    return taskDataManager.findTaskCountByNativeQuery(parameterMap);
  }

  @Override
  public List<Task> findTasksByParentTaskId(String parentTaskId) {
    return taskDataManager.findTasksByParentTaskId(parentTaskId);
  }

  @Override
  public void deleteTask(String taskId, String deleteReason, boolean cascade) {
    
    TaskEntity task = findById(taskId);

    if (task != null) {
      if (task.getExecutionId() != null) {
        throw new ActivitiException("The task cannot be deleted because is part of a running process");
      }
      
      if (Activiti5Util.isActiviti5ProcessDefinitionId(getCommandContext(), task.getProcessDefinitionId())) {
        Activiti5CompatibilityHandler activiti5CompatibilityHandler = Activiti5Util.getActiviti5CompatibilityHandler(); 
        activiti5CompatibilityHandler.deleteTask(taskId, deleteReason, cascade);
        return;
      }

      String reason = (deleteReason == null || deleteReason.length() == 0) ? TaskEntity.DELETE_REASON_DELETED : deleteReason;
      deleteTask(task, reason, cascade, false);
    } else if (cascade) {
      getHistoricTaskInstanceEntityManager().delete(taskId);
    }
  }

  @Override
  public void updateTaskTenantIdForDeployment(String deploymentId, String newTenantId) {
    taskDataManager.updateTaskTenantIdForDeployment(deploymentId, newTenantId);
  }

  public TaskDataManager getTaskDataManager() {
    return taskDataManager;
  }

  public void setTaskDataManager(TaskDataManager taskDataManager) {
    this.taskDataManager = taskDataManager;
  }
  
}
