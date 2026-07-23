import org.jspecify.annotations.NullMarked;

@NullMarked
open module ms.rohde.businesscalendarrelay {
    requires org.jspecify;
    requires jakarta.inject;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.beans;
    requires ms.rohde.hexagonalarch.annotations;
    requires ms.rohde.hexagonalarch.spring;
}
