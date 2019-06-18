In this POC project, the main language runner is Java which proxies to 
Python implementation when executing specs

**Prerequisites**
- `brew install gauge`
- Python3
- `pip3 install gauge`
- Java 11
- Maven 3.6

### Step 1

- Write [specs](src/specs) as usual

```
# Spec with Python implementation

## Scenario with steps having static parameters

* Python simple step
* Python step with multiple arguments "hello" and "1"
* Python step to be "failed"
```

### Step 2

- Write [proxied implementations](src/test/java/org/mdkt/gauge/SpecPython.java) in Java and annotated with `LanguageRunner.python`

```java
@ProxyStep(LanguageRunner.python)
@Step("Python simple step")
public void pythonSimpleStep() {
}

@ProxyStep(LanguageRunner.python)
@Step("Python step with multiple arguments <s> and <i>")
public void pythonStepMultiple(String s, int i) {
}

@ProxyStep(LanguageRunner.python)
@Step("Python step to be <status>")
public void pythonStepToBe(String status) {
}
```

### Step 3
 
- Write [Python implementation](step_impl/spec_python.py)

```python
from getgauge.python import Messages
from getgauge.python import step


@step("Python simple step")
def simple_step():
    Messages.write_message("Hello from Python")


@step("Python step with multiple arguments <s> and <i>")
def step_multiple_args(s, i):
    Messages.write_message("Values are {} and {}".format(s, i))


@step("Python step to be <status>")
def step_status(status):
    assert status == "success"
```

### Step 4

- Run `mvn test`

```
...<snip>...
# Spec with Java implementation
  ## Test Scenario	 ✔

# Spec with Python implementation
  ## Scenario with steps having static parameters	 ✔ ✔ ✘
        Failed Step: Python step to be "failed"
...<snip>...
```

---

[![Gauge Badge](https://gauge.org/Gauge_Badge.svg)](https://gauge.org)
