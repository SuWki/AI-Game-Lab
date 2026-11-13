package com.aigamelab.tester;
import com.aigamelab.domain.Run;
import com.aigamelab.repository.RunRepository;
/** Direct executor integration tests must use a new active Run, never reopen a completed one. */
public final class ActiveTestRun {
    private ActiveTestRun(){}
    public static Run after(RunRepository repository,Run completed){return repository.insert(new Run(null,completed.taskId(),"RUNNING",completed.promptVersionId(),completed.model(),0,completed.maxSteps(),completed.tokenBudget(),0,null,null,null,null,null));}
}
