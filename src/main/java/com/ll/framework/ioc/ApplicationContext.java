package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.framework.ioc.annotations.Repository;
import com.ll.framework.ioc.annotations.Service;
import com.ll.standard.util.Ut;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class ApplicationContext {

    private Map<Class<?>, BeanDefinition> beanMap;
    private Map<String, Object> beanContainer;
    private String basePackage;

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
        this.beanMap = new HashMap<>();
        this.beanContainer = new HashMap<>();
    }

    public void init() {
        //빈 등록
        Set<Class<?>> classes = findClass(basePackage);
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(Component.class)) {
                registerBean(clazz, Component.class);
            }
            else if (clazz.isAnnotationPresent(Service.class)) {
                registerBean(clazz,Service.class);
            }
            else if (clazz.isAnnotationPresent(Repository.class)) {
                registerBean(clazz, Repository.class);
            }
            else if (clazz.isAnnotationPresent(Configuration.class)) {
                registerBean(clazz, Configuration.class);
            }
        }

        //의존성 주입
        Set<BeanDefinition> remainingBeans = new HashSet<>(beanMap.values());
        while (!remainingBeans.isEmpty()){
            int preSize = remainingBeans.size();
            Iterator<BeanDefinition> it = remainingBeans.iterator();
            while (it.hasNext()){
                BeanDefinition beanDefinition = it.next();
                Object bean = injectDependence(beanDefinition);
                if (bean != null){
                    beanContainer.put(beanDefinition.getBeanName(), bean);
                    it.remove();
                }
            }
            int postSize = remainingBeans.size();
            if (preSize == postSize){
                throw new RuntimeException("의존성 주입이 불가능한 빈이 존재합니다.");
            }
        }
    }

    public void registerBean(Class<?> clazz, Class<?> annotationType){
        try {
            Class<?>[] parameterTypes = Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> Modifier.isFinal(field.getModifiers()))
                .map(Field::getType)
                .toArray(Class<?>[]::new);

            Constructor<?> constructor = clazz.getConstructor(parameterTypes);

            String beanName = Ut.str.lcfirst(clazz.getSimpleName());
            beanMap.put(clazz, new BeanDefinition(beanName, annotationType, constructor, parameterTypes));
        } catch (Exception e){
            throw new RuntimeException("빈 등록 중 오류 발생"+clazz.toString(), e);
        }
    }

    public Object injectDependence(BeanDefinition beanDefinition) {
        try {
            String beanName = beanDefinition.getBeanName();
            if (beanContainer.containsKey(beanName)) {
                return beanContainer.get(beanName);
            }

            Object[] parameters = new Object[beanDefinition.getParameterTypes().length];

            for (int i = 0; i < beanDefinition.getParameterTypes().length; i++) {
                Class<?> parameterType = beanDefinition.getParameterTypes()[i];

                BeanDefinition parameterBeanDefinition = null;
                for (Class<?> beanType : beanMap.keySet()) {
                    if (beanType.equals(parameterType)) {
                        parameterBeanDefinition = beanMap.get(beanType);
                        break;
                    }
                }

                if (parameterBeanDefinition == null) {
                    throw new RuntimeException(
                        String.format("의존성 %s를 찾을 수 없습니다.", parameterType.getName())
                    );
                }

                Object dependencyBean = injectDependence(parameterBeanDefinition);
                parameters[i] = dependencyBean;

                if (!beanContainer.containsKey(parameterBeanDefinition.getBeanName())) {
                    beanContainer.put(parameterBeanDefinition.getBeanName(), dependencyBean);
                }
            }

            Object bean = beanDefinition.getConstructor().newInstance(parameters);
            beanContainer.put(beanName, bean);
            return bean;

        } catch (Exception e) {
            throw new RuntimeException(
                String.format("Bean %s 생성 중 오류 발생", beanDefinition.getBeanType().getName()),
                e
            );
        }
    }

    public <T> T genBean(String beanName) {
        beanName = Ut.str.lcfirst(beanName);
        if (beanContainer.containsKey(beanName)) {
            return (T) beanContainer.get(beanName);
        }
        return null;
    }

    private Set<Class<?>> findClass(String basePackage) {
        Set<Class<?>> classes = new HashSet<>();
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            String path = basePackage.replace('.', '/');

            Enumeration<URL> resources = classLoader.getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();

                if (resource.getProtocol().equals("file")) {
                    classes.addAll(findDirectory(new File(resource.getFile()), basePackage));
                }

            }
        } catch (IOException e) {
            throw new RuntimeException("패키지 스캔 중 오류 발생", e);
        }

        return classes.stream()
            .filter(clazz -> !clazz.getPackageName().endsWith(".annotations"))
            .collect(Collectors.toSet());
    }

    private Set<Class<?>> findDirectory(File directory, String packageName) {
        Set<Class<?>> classes = new HashSet<>();

        if (!directory.exists()) {
            return classes;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    classes.addAll(findDirectory(file, packageName + "." + file.getName()));
                } else if (file.getName().endsWith(".class")) {
                    try {
                        String className = packageName + '.' +
                            file.getName().substring(0, file.getName().length() - 6);
                        classes.add(Class.forName(className));
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException("클래스를 찾지 못했습니다.", e);
                    }
                }
            }
        }
        return classes;
    }

    @Getter
    public static class BeanDefinition{
        private String beanName;
        private Class<?> beanType;
        private Constructor<?> constructor;
        private Class<?>[] parameterTypes;

        public BeanDefinition(
            String beanName,
            Class<?> beanType,
            Constructor constructor,
            Class<?>[] parameterTypes) {
            this.beanName = beanName;
            this.beanType = beanType;
            this.constructor = constructor;
            this.parameterTypes = parameterTypes;
        }
    }
}
