package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** 旧 picker 监听器退役后，仍保留全仓事件订阅参数的类型契约，防止注册期崩溃。 */
public class BlockPickerEventRegistrationContractTest {

    @Test
    public void noSubscribeEventBindsFmlLifecycleEventPackage() throws Exception {
        List<CompiledClasses.SubscriberMethod> subscriptions = CompiledClasses.subscribeEventMethods();
        Assert.assertTrue("必须真的扫到事件总线订阅（守卫不得空跑），实际 " + subscriptions.size(),
                subscriptions.size() >= 8);
        List<String> violations = new ArrayList<String>();
        for (CompiledClasses.SubscriberMethod subscription : subscriptions) {
            List<String> parameters = subscription.method.parameterInternalNames();
            if (parameters.size() != 1) {
                violations.add(subscription + " 的参数个数必须为 1，实际 " + parameters.size());
                continue;
            }
            if (!CompiledClasses.isSubtypeOf(parameters.get(0), CompiledClasses.EVENT_BASE)) {
                violations.add(subscription + " -> " + parameters.get(0) + " 不是 Event 子类");
            }
        }
        Assert.assertTrue("@SubscribeEvent 的参数必须是 Forge Event 子类（FML 生命周期事件不是，注册期即崩）: "
                + violations, violations.isEmpty());
    }
}
