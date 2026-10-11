package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.model.*;
import org.junit.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RunBudgetTerminalAccountingTest {
    private final String run=UUID.randomUUID().toString();
    private ModelUsageAccounting ledger;
    @Before public void setup(){ledger=mock(ModelUsageAccounting.class);ModelUsageLedger.install(ledger);}
    @After public void cleanup(){ModelUsageLedger.uninstall(ledger);RunBudgetRegistry.finish(run);org.slf4j.MDC.clear();}
    private RunBudgetRegistry.State start(){return RunBudgetRegistry.start(run,"alice",new RunBudgetRegistry.Limits(Duration.ofMinutes(1),1,1,100));}
    private void failFirstTerminal(){doThrow(new IllegalStateException("synthetic database outage")).doNothing()
            .when(ledger).changeRunState(anyString(),anyString(),anyString());}
    @Test public void finishSignalsLocallyAndRetryPersistsAfterDatabaseRecovery(){
        var state=start();failFirstTerminal();RunBudgetRegistry.finish(run);
        assertThrows(RunBudgetExceededException.class,state::checkActive);
        assertThrows(RunBudgetExceededException.class,()->state.termination().block(Duration.ofSeconds(1)));
        RunBudgetRegistry.finish(run);RunBudgetRegistry.finish(run);
        verify(ledger,times(2)).changeRunState(run,"alice","COMPLETED");
    }
    @Test public void cancellationRevokesMaintenanceEvenWhenTerminalWriteFails(){
        var state=start();org.slf4j.MDC.put("runId",run);
        Runnable maintenance=RunBudgetRegistry.captureMaintenance(()->fail("cancelled maintenance cannot execute"));
        failFirstTerminal();state.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,state::checkActive);
        assertThrows(java.util.concurrent.CancellationException.class,maintenance::run);
        assertThrows(java.util.concurrent.CancellationException.class,()->state.termination().block(Duration.ofSeconds(1)));
        state.cancel();verify(ledger,times(2)).changeRunState(run,"alice","CANCELLED");
    }
    @Test public void exhaustionRetainsBudgetFailureAndRetriesLedgerOnFinish(){
        var state=start();state.reserveModel(1,0);failFirstTerminal();
        assertThrows(RunBudgetExceededException.class,()->state.reserveModel(1,0));
        assertThrows(RunBudgetExceededException.class,state::checkActive);
        RunBudgetRegistry.finish(run);verify(ledger,times(2)).changeRunState(run,"alice","EXHAUSTED");
    }
    @Test public void admissionAndReviewTransitionsStillFailClosed(){
        var state=start();doThrow(new IllegalStateException("synthetic outage")).when(ledger).changeRunState(run,"alice","WAITING_REVIEW");
        assertThrows(IllegalStateException.class,state::awaitReview);assertFalse(state.waitingForReview());
        String other=UUID.randomUUID().toString();doThrow(new IllegalStateException("synthetic outage"))
                .when(ledger).openRun(eq(other),eq("alice"),anyLong(),eq(false));
        assertThrows(IllegalStateException.class,()->RunBudgetRegistry.start(other,"alice",new RunBudgetRegistry.Limits(Duration.ofMinutes(1),1,1,100)));
        assertNull(RunBudgetRegistry.find(other));
    }
}
