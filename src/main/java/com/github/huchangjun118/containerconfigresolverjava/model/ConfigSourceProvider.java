package com.github.huchangjun118.containerconfigresolverjava.model;

/**
 * 来源读取抽象：运行期来源变化时，由 resolver 调用该接口重新采集一个完整版本。
 * 实现方必须保证返回的 {@link ConfigSourceVersion} 内部自洽
 * （各来源来自同一采样时刻），读取失败可直接抛出异常，resolver 会隔离失败。
 */
@FunctionalInterface
public interface ConfigSourceProvider {

    ConfigSourceVersion load() throws Exception;
}
