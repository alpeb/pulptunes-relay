$('.statefulBtn').click(function() {
  $(this).button('loading');
  $(this).parents('form').submit();
});

