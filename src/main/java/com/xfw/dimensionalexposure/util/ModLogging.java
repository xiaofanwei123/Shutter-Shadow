package com.xfw.dimensionalexposure.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/** 统一开关本模组日志，保留原版、其它模组和日志级别设置。 */
public final class ModLogging {
    // 配置加载前保持关闭，避免关闭日志时仍输出初始化消息。
    private static volatile boolean enabled = false;
    private static LoggerContext context;
    private static Configuration installedConfiguration;

    /** 工具类私有构造器。 */
    private ModLogging() {}

    /** 应用日志总开关，并在首次调用时安装过滤器及日志配置重载监听。 */
    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (context == null) {
            context = (LoggerContext) LogManager.getContext(false);
            context.addPropertyChangeListener(event -> {
                if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                        && event.getNewValue() instanceof Configuration configuration) {
                    install(configuration);
                }
            });
        }
        install(context.getConfiguration());
    }

    /** 每个日志配置仅安装一次过滤器，重载后重新安装。 */
    private static synchronized void install(Configuration configuration) {
        if (installedConfiguration == configuration) return;
        LogFilter filter = new LogFilter();
        filter.start();
        configuration.addFilter(filter);
        installedConfiguration = configuration;
    }

    /** 为本模组命名日志提供统一过滤，其他日志沿用原有处理。 */
    private static final class LogFilter extends AbstractFilter {
        /** 关闭时拒绝本模组日志，其他情况不改变日志级别与过滤结果。 */
        private Result result(String loggerName) {
            return !enabled && ("dimensional_exposure".equals(loggerName)
                    || loggerName != null && loggerName.startsWith("com.xfw.dimensionalexposure."))
                    ? Result.DENY : Result.NEUTRAL;
        }

        /** 过滤已创建的日志事件。 */
        @Override
        public Result filter(LogEvent event) {
            return result(event.getLoggerName());
        }

        /** 过滤字符串及不同数量的占位符参数。 */
        @Override
        public Result filter(Logger logger, Level level, Marker marker, String message, Object... parameters) {
            return result(logger.getName());
        }

        /** 过滤普通对象或带异常的字符串消息。 */
        @Override
        public Result filter(Logger logger, Level level, Marker marker, Object message, Throwable throwable) {
            return result(logger.getName());
        }

        /** 过滤结构化消息及 SLF4J 转交的消息。 */
        @Override
        public Result filter(Logger logger, Level level, Marker marker, Message message, Throwable throwable) {
            return result(logger.getName());
        }
    }
}
