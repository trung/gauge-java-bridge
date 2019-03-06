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
