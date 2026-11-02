package com.otr.portal.hosts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.otr.portal.metadata.config.ApplicationProperties;
import com.otr.portal.metadata.config.ServerProperties;
import com.otr.portal.metadata.config.UpdateHook;
import com.otr.portal.metadata.extensions.ExtensibleConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * @author pobedenniy.alexey
 * @since 21.09.17
 */
@UpdateHook(HostsUpdateHook.class)
public class HostsConfiguration extends ExtensibleConfiguration {

    @ServerProperties(name = "Хост Портала",
            description = "Задает доменное имя на котором Портал доступен пользователям. Данная настройка используется при формировании ссылок на страницы. Может указывать как на сам Портал, так и на внешний сервер (при работе через фронтэнд-сервер)." +
                    " Имя может задаваться как в полном формате http://host:port, так и в сокращенном host:port или host",
            example = "test-portal.otr.ru")
    private String entryPoint;
    @ServerProperties(name = "Локальный хост Портала", description = "Если настройка включена, то считается что \"хост Портала\" указывает на этот сервер и в этом случае порт Портала дописывается автоматически")
    private boolean localEntryPoint = true;
    @ApplicationProperties(name = "Группы бэкендов", description = "Позволяет сгруппировать бэкенды и переопределять некоторые параметры для группы в целом")
    private List<HostGroup> hostGroups;
    @ApplicationProperties(name = "Бэкенды")
    private List<Host> hosts;
    @ApplicationProperties(
            name = "Различать бэкенды по uri",
            description = "Если настройка включена, то каждому бэкенду назначается uri, на котором будет доступен контент этого бэкенда, например http://portal.domain/my_backend/. Если настройка выключена, то бэкенды определяются по доменному имени запроса, например http://backend.portal.domain/",
            warning = "Изменение настройки требует перезапуска сервера. Использование разделения бэкендов по доменным именам обеспечивает лучшую совместимость")
    private boolean useUris = false;
    @ApplicationProperties(
            name = "Отдельный пул потоков для навигации бекенда",
            description = "Эта настройка позволяет создавать отдельный пул потоков для каждого бекенда навигации. Когда эта настройка включена, каждый бекенд будет иметь свой собственный пул потоков для обработки команд навигации. Это гарантирует, что переполнение одного пула потоков, вызванное одним бекендом, не повлияет на работу других бекендов.",
            warning = "Изменение настройки требует перезапуска сервера. Использование этой настройки может увеличить потребление памяти, поскольку необходимо создавать отдельный пул потоков для каждого бекенда.")
    private boolean useIsolateNavigationThreadPool = false;
    @ApplicationProperties(
            name = "Сохранять последний запрошенный бэкенд в сессии пользователя",
            description = "Если настройка включена, то при каждом запросе от пользователя бэкенд, на который был перенаправлен этот запрос, сохраняется в сессии пользователя")
    private boolean saveBackendInSession = false;

    public HostsConfiguration() {
    }

    public List<Host> getHosts() {
        return hosts;
    }

    @JsonIgnore
    public Stream<Host> allHosts() {
        return Stream
                .concat(ofNullable(this.getHosts()).map(List::stream).orElse(Stream.empty()),
                        ofNullable(this.hostGroups).map(List::stream).orElse(Stream.empty())
                                .map(HostGroup::getHosts)
                                .filter(Objects::nonNull)
                                .flatMap(List::stream)
                );
    }

    public void setHosts(List<Host> hosts) {
        this.hosts = hosts;
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    public boolean isLocalEntryPoint() {
        return localEntryPoint;
    }

    public void setEntryPoint(String entryPoint) {
        this.entryPoint = entryPoint;
    }

    public void setLocalEntryPoint(boolean localEntryPoint) {
        this.localEntryPoint = localEntryPoint;
    }

    public boolean isUseUris() {
        return useUris;
    }

    public void setUseUris(boolean useUris) {
        this.useUris = useUris;
    }

    public List<HostGroup> getHostGroups() {
        return hostGroups;
    }

    public void setHostGroups(List<HostGroup> hostGroups) {
        this.hostGroups = hostGroups;
    }

    @JsonIgnore
    public List<Host> mergedHosts() {
        final Stream<Host> hostStream = ofNullable(this.hostGroups).map(List::stream).map(this::transformGroup).orElse(Stream.empty());
        return Stream.concat(
                ofNullable(this.hosts).map(List::stream).orElse(Stream.empty()),
                hostStream
        ).collect(Collectors.toList());
    }

    @JsonIgnore
    private Stream<Host> transformGroup(Stream<HostGroup> hostGroupStream) {
        return hostGroupStream.flatMap(HostGroup::mergedHosts);
    }

    public void addGroup(HostGroup hostGroup) {
        if (this.hostGroups == null) {
            this.hostGroups = new ArrayList<>();
        }
        this.hostGroups.add(hostGroup);
    }

    public boolean isSaveBackendInSession() {
        return saveBackendInSession;
    }

    public void setSaveBackendInSession(boolean saveBackendInSession) {
        this.saveBackendInSession = saveBackendInSession;
    }

    public boolean isUseIsolateNavigationThreadPool() {
        return useIsolateNavigationThreadPool;
    }

    public void setUseIsolateNavigationThreadPool(boolean useIsolateNavigationThreadPool) {
        this.useIsolateNavigationThreadPool = useIsolateNavigationThreadPool;
    }
}
