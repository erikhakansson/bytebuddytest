package cl;

import net.bytebuddy.implementation.bind.annotation.BindingPriority;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.util.concurrent.Callable;

/**
 * Created by Erik Håkansson on 2016-06-27.
 * WirelessCar
 */
public class PackagePrivateInterceptor {

    @RuntimeType
    @BindingPriority(1)
    public static Object intercept(@SuperCall Callable<?> superCall, @Origin Class targetClass, @Origin String method)
        throws Exception {

        Class callingClass = new InternalSecurityManager().getCallingClass();
        String targetPackage = targetClass.getPackage().getName();
        if (!callingClass.getPackage().getName().equals(targetPackage)) {
            throw new IllegalAccessError(callingClass + " cannot access method " + method + " of Class " + targetClass);
        }

        //Default:
        return superCall.call();
    }

    private static class InternalSecurityManager extends SecurityManager {
        Class getCallingClass() {
            Class[] classContext = getClassContext();
            for (Class current : classContext) {
                if (current.getName().startsWith("java.") ||
                    current.getName().equals(PackagePrivateInterceptor.class.getName()) ||
                    current.getName().equals(InternalSecurityManager.class.getName())) {
                    continue;
                }
                return current;
            }
            throw new IllegalStateException("Failed to find calling Class");
        }
    }
}
